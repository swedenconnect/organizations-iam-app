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
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.swedenconnect.iam.admin.controllers.dto.CreateOrganizationRequest;
import se.swedenconnect.iam.admin.controllers.dto.UpdateOrganizationRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;

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

  private final KeycloakAdminClient keycloakAdminClient;

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

    final HttpSession session = request.getSession(false);
    final Object attr = session != null
        ? session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)
        : null;

    if (!(attr instanceof final AdminSessionData data && data.currentUserIsSuperuser())) {
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

    if (this.keycloakAdminClient.organizationExists(orgNumber)) {
      log.info("POST /api/organizations — rejected: organization '{}' already exists", orgNumber);
      return ResponseEntity.status(409).build();
    }

    this.keycloakAdminClient.createOrganization(orgNumber, nameSv, nameEn);
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

    final HttpSession session = request.getSession(false);
    final Object attr = session != null
        ? session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)
        : null;

    if (!(attr instanceof final AdminSessionData data)) {
      log.info("PUT /api/organizations/{} — rejected: no valid admin session", orgIdentifier);
      return ResponseEntity.status(403).build();
    }

    final boolean isSuperuser = data.currentUserIsSuperuser();

    // Non-superusers may not change org names
    final boolean hasNameChange = (req.nameSv() != null && !req.nameSv().isBlank())
        || (req.nameEn() != null && !req.nameEn().isBlank());
    if (!isSuperuser && hasNameChange) {
      log.info("PUT /api/organizations/{} — rejected: non-superuser attempted name change", orgIdentifier);
      return ResponseEntity.status(403).build();
    }

    // Non-superusers must have org-level admin right
    if (!isSuperuser && !hasOrgAdminRight(data, orgIdentifier)) {
      log.info("PUT /api/organizations/{} — rejected: caller lacks org-admin right", orgIdentifier);
      return ResponseEntity.status(403).build();
    }

    // Superuser: validate name fields if provided
    if (isSuperuser) {
      if (req.nameSv() != null && req.nameSv().isBlank()) {
        return ResponseEntity.badRequest().body("nameSv must not be blank");
      }
      if (req.nameEn() != null && req.nameEn().isBlank()) {
        return ResponseEntity.badRequest().body("nameEn must not be blank");
      }
    }

    try {
      this.keycloakAdminClient.updateOrganization(
          orgIdentifier,
          isSuperuser ? req.nameSv() : null,
          isSuperuser ? req.nameEn() : null,
          req.contactEmail(),
          req.contactPhone());
      log.info("PUT /api/organizations/{} — updated successfully", orgIdentifier);

      // Re-read to return current state
      final OrganizationInfo updated = this.keycloakAdminClient.fetchAllOrganizationGroups()
          .stream()
          .filter(o -> orgIdentifier.equals(o.orgIdentifier()))
          .findFirst()
          .orElse(null);

      if (updated == null) {
        return ResponseEntity.notFound().build();
      }

      final java.util.LinkedHashMap<String, Object> responseBody = new java.util.LinkedHashMap<>();
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

    final HttpSession session = request.getSession(false);
    final Object attr = session != null
        ? session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)
        : null;

    if (!(attr instanceof final AdminSessionData data && data.currentUserIsSuperuser())) {
      log.info("DELETE /api/organizations/{} — rejected: caller is not a superuser", orgIdentifier);
      return ResponseEntity.status(403).build();
    }

    if (!this.keycloakAdminClient.organizationExists(orgIdentifier)) {
      log.info("DELETE /api/organizations/{} — not found", orgIdentifier);
      return ResponseEntity.notFound().build();
    }

    if (this.keycloakAdminClient.isFunctionAttachedToOrg_any(orgIdentifier)) {
      log.info("DELETE /api/organizations/{} — rejected: org has attached functions", orgIdentifier);
      return ResponseEntity.status(409).body("Organization has attached functions; detach them first");
    }

    try {
      this.keycloakAdminClient.deleteOrganization(orgIdentifier);
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

  private static boolean hasOrgAdminRight(
      final AdminSessionData data,
      final String orgIdentifier) {
    return data.claim().orgEntries().stream()
        .filter(e -> orgIdentifier.equals(e.orgIdentifier().toString()))
        .flatMap(e -> e.functions().stream())
        .anyMatch(f -> "*".equals(f.function()) && "admin".equals(f.right()));
  }

}
