/*
 * Copyright 2026 Sweden Connect
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.swedenconnect.iam.admin.controllers;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.swedenconnect.iam.admin.controllers.dto.CreateOrganizationRequest;
import se.swedenconnect.iam.admin.controllers.dto.OrganizationPageResponse;
import se.swedenconnect.iam.admin.controllers.dto.OrganizationResponse;
import se.swedenconnect.iam.admin.controllers.dto.UpdateOrganizationRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;
import se.swedenconnect.iam.admin.service.OrganizationService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for organization management operations.
 *
 * <p>Only superusers may create organizations. Non-superusers receive HTTP 403.</p>
 *
 * @author Martin Lindström
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class OrganizationController {

  private final OrganizationService organizationService;

  private static final int DEFAULT_PAGE_SIZE = 50;

  /**
   * Returns a paginated list of organizations, optionally filtered by a search term.
   *
   * <p>Superusers receive all organizations from the cache. Regular admins receive only their
   * authorized organizations (from session). When {@code search} is provided, results are
   * filtered server-side before pagination is applied.</p>
   *
   * @param page    0-based page index (default 0)
   * @param size    page size (default 50)
   * @param search  optional case-insensitive filter on org number, Swedish name and English name
   * @param request the HTTP servlet request
   * @return 200 with paginated organizations, 403 if not authenticated
   */
  @GetMapping(value = "/organizations", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<OrganizationPageResponse> listOrganizations(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) final int size,
      @RequestParam(defaultValue = "") final String search,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null) {
      log.info("GET /api/organizations — rejected: no valid admin session");
      return ResponseEntity.status(403).build();
    }

    final int effectiveSize = size > 0 ? size : DEFAULT_PAGE_SIZE;
    final int effectivePage = Math.max(page, 0);
    final String searchTerm = (search == null || search.isBlank()) ? null : search.trim();

    final OrganizationPageResponse result;

    if (data.currentUserIsSuperuser()) {
      final String orgConstraint = data.orgConstraint();
      if (orgConstraint != null) {
        result = this.organizationService.listByOrgNumbers(
            List.of(orgConstraint), searchTerm, effectivePage, effectiveSize);
      }
      else if (searchTerm != null) {
        result = this.organizationService.searchByName(searchTerm, effectivePage, effectiveSize);
      }
      else {
        result = this.organizationService.list(effectivePage, effectiveSize);
      }
      log.debug("GET /api/organizations — superuser, page={}, size={}, search='{}', total={}",
          effectivePage, effectiveSize, search, result.totalElements());
    }
    else {
      final java.util.Set<String> scope = data.adminOrgIdentifiers();
      final List<String> orgIds;
      if (data.orgConstraint() != null) {
        final String oc = data.orgConstraint();
        orgIds = scope.contains(oc) ? List.of(oc) : List.of();
      }
      else {
        orgIds = scope.stream().sorted().toList();
      }
      result = this.organizationService.listByOrgNumbers(orgIds, searchTerm, effectivePage, effectiveSize);
      log.debug("GET /api/organizations — regular admin, page={}, size={}, search='{}', total={}",
          effectivePage, effectiveSize, search, result.totalElements());
    }

    return ResponseEntity.ok(applyFunctionConstraint(result, data.functionConstraint()));
  }

  /**
   * Returns a single organization by its identifier.
   *
   * @param orgIdentifier the organization identifier
   * @param request       the HTTP servlet request
   * @return 200 with the organization, 403 if not authenticated or no access, 404 if not found
   */
  @GetMapping(value = "/organizations/{orgIdentifier}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> getOrganization(
      @PathVariable final String orgIdentifier,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null) {
      log.info("GET /api/organizations/{} — rejected: no valid admin session", orgIdentifier);
      return ResponseEntity.status(403).build();
    }

    if (!data.currentUserIsSuperuser() && !data.adminOrgIdentifiers().contains(orgIdentifier)) {
      log.info("GET /api/organizations/{} — rejected: caller lacks org access", orgIdentifier);
      return ResponseEntity.status(403).build();
    }

    return this.organizationService.findByOrgNumber(orgIdentifier)
        .<ResponseEntity<?>>map(o -> ResponseEntity.ok(toResponse(o)))
        .orElseGet(() -> {
          log.info("GET /api/organizations/{} — not found", orgIdentifier);
          return ResponseEntity.notFound().build();
        });
  }

  /**
   * Creates a new organization in Keycloak.
   *
   * @param req     the request body
   * @param request the HTTP servlet request
   * @return 201 Created with the created organization, 400 on invalid input, 403 if not superuser,
   *         409 if org number already exists
   */
  @PostMapping(value = "/organizations",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> createOrganization(
      @RequestBody final CreateOrganizationRequest req,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null || !data.currentUserIsSuperuser()) {
      log.info("POST /api/organizations — rejected: caller is not a superuser");
      return ResponseEntity.status(403).build();
    }

    final String orgNumber = req.organizationNumber();
    final String nameSv = req.nameSv();
    final String nameEn = req.nameEn();

    if (!orgNumber.matches("^\\d{10}$")) {
      log.info("POST /api/organizations — rejected: invalid organization number '{}'", orgNumber);
      return ResponseEntity.badRequest().body("organizationNumber must be exactly 10 digits");
    }
    if (nameSv.isBlank()) {
      return ResponseEntity.badRequest().body("nameSv must not be blank");
    }
    if (nameEn.isBlank()) {
      return ResponseEntity.badRequest().body("nameEn must not be blank");
    }

    if (this.organizationService.exists(orgNumber)) {
      log.info("POST /api/organizations — rejected: organization '{}' already exists", orgNumber);
      return ResponseEntity.status(409).build();
    }

    this.organizationService.create(orgNumber, nameSv, nameEn);
    log.info("POST /api/organizations — organization '{}' created successfully", orgNumber);

    return ResponseEntity.status(201).body(Map.of(
        "id", orgNumber,
        "organizationNumber", orgNumber,
        "nameSv", nameSv,
        "nameEn", nameEn));
  }

  /**
   * Updates an organization's mutable fields in Keycloak.
   *
   * <p>Superusers may update names, contact email, and contact phone. Non-superuser org-admins
   * may only update contact email and contact phone; sending name fields returns 403.</p>
   *
   * @param orgIdentifier the organization identifier
   * @param req           the request body
   * @param request       the HTTP servlet request
   * @return 200 with updated organization data, 400 on validation error,
   *         403 if unauthorized, 404 if org not found, 500 on Keycloak error
   */
  @PutMapping(value = "/organizations/{orgIdentifier}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> updateOrganization(
      @PathVariable final String orgIdentifier,
      @RequestBody final UpdateOrganizationRequest req,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null) {
      log.info("PUT /api/organizations/{} — rejected: no valid admin session", orgIdentifier);
      return ResponseEntity.status(403).build();
    }

    final boolean isSuperuser = data.currentUserIsSuperuser();

    final boolean hasNameChange = (req.nameSv() != null && !req.nameSv().isBlank())
        || (req.nameEn() != null && !req.nameEn().isBlank());
    if (!isSuperuser && hasNameChange) {
      log.info("PUT /api/organizations/{} — rejected: non-superuser attempted name change", orgIdentifier);
      return ResponseEntity.status(403).build();
    }

    if (!isSuperuser && !hasOrgAdminRight(data, orgIdentifier)) {
      log.info("PUT /api/organizations/{} — rejected: caller lacks org-admin right", orgIdentifier);
      return ResponseEntity.status(403).build();
    }

    if (isSuperuser) {
      if (req.nameSv() != null && req.nameSv().isBlank()) {
        return ResponseEntity.badRequest().body("nameSv must not be blank");
      }
      if (req.nameEn() != null && req.nameEn().isBlank()) {
        return ResponseEntity.badRequest().body("nameEn must not be blank");
      }
    }

    try {
      final OrganizationInfo updated = this.organizationService.update(
          orgIdentifier,
          isSuperuser ? req.nameSv() : null,
          isSuperuser ? req.nameEn() : null,
          req.contactEmail(),
          req.contactPhone());
      log.info("PUT /api/organizations/{} — updated successfully", orgIdentifier);

      final LinkedHashMap<String, Object> responseBody = new LinkedHashMap<>();
      responseBody.put("orgIdentifier", updated.orgIdentifier());
      responseBody.put("nameSv", updated.name().get("sv"));
      responseBody.put("nameEn", updated.name().get("en"));
      responseBody.put("contactEmail", updated.contactEmail());
      responseBody.put("contactPhone", updated.contactPhone());
      return ResponseEntity.ok(responseBody);
    }
    catch (final KeycloakAdminException e) {
      if (e.getMessage() != null && e.getMessage().contains("not found")) {
        log.info("PUT /api/organizations/{} — not found: {}", orgIdentifier, e.getMessage());
        return ResponseEntity.notFound().build();
      }
      log.error("PUT /api/organizations/{} — Keycloak error: {}", orgIdentifier, e.getMessage());
      return ResponseEntity.status(500).body(e.getMessage());
    }
  }

  /**
   * Deletes an organization from Keycloak.
   *
   * <p>Only superusers may perform this operation. Returns 409 if the organization still
   * has functions attached — the caller must detach all functions first.</p>
   *
   * @param orgIdentifier the organization identifier
   * @param request       the HTTP servlet request
   * @return 204 No Content on success; 403 if not superuser; 404 if not found;
   *         409 if the org has attached functions; 500 on Keycloak error
   */
  @DeleteMapping("/organizations/{orgIdentifier}")
  public ResponseEntity<?> deleteOrganization(
      @PathVariable final String orgIdentifier,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null || !data.currentUserIsSuperuser()) {
      log.info("DELETE /api/organizations/{} — rejected: caller is not a superuser", orgIdentifier);
      return ResponseEntity.status(403).build();
    }

    if (!this.organizationService.exists(orgIdentifier)) {
      log.info("DELETE /api/organizations/{} — not found", orgIdentifier);
      return ResponseEntity.notFound().build();
    }

    if (this.organizationService.hasFunctionsAttached(orgIdentifier)) {
      log.info("DELETE /api/organizations/{} — rejected: org has attached functions", orgIdentifier);
      return ResponseEntity.status(409).body("Organization has attached functions; detach them first");
    }

    try {
      this.organizationService.delete(orgIdentifier);
      log.info("DELETE /api/organizations/{} — deleted successfully", orgIdentifier);
      return ResponseEntity.noContent().build();
    }
    catch (final KeycloakAdminException e) {
      log.error("DELETE /api/organizations/{} — Keycloak error: {}", orgIdentifier, e.getMessage());
      return ResponseEntity.status(500).body(e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static OrganizationPageResponse applyFunctionConstraint(
      final OrganizationPageResponse response,
      final @Nullable String functionConstraint) {
    if (functionConstraint == null) {
      return response;
    }
    final List<OrganizationResponse> filtered = response.content().stream()
        .map(o -> new OrganizationResponse(
            o.orgIdentifier(),
            o.nameSv(),
            o.nameEn(),
            o.groupId(),
            o.attachedFunctions().stream().filter(functionConstraint::equals).toList(),
            o.contactEmail(),
            o.contactPhone()))
        .toList();
    return new OrganizationPageResponse(
        filtered, response.totalElements(), response.page(), response.size(), response.totalPages());
  }

  private static OrganizationResponse toResponse(final OrganizationInfo o) {
    return new OrganizationResponse(
        o.orgIdentifier(),
        o.name().get("sv"),
        o.name().get("en"),
        o.groupId(),
        o.attachedFunctions(),
        o.contactEmail(),
        o.contactPhone());
  }

  private static boolean hasOrgAdminRight(
      final AdminSessionData data,
      final String orgIdentifier) {
    return data.claim().orgEntries().stream()
        .filter(e -> orgIdentifier.equals(e.orgIdentifier().toString()))
        .flatMap(e -> e.functions().stream())
        .anyMatch(f -> "*".equals(f.function()) && "admin".equals(f.right()));
  }
}
