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
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.CreateFunctionRequest;
import se.swedenconnect.iam.admin.controllers.dto.FunctionResponse;
import se.swedenconnect.iam.admin.controllers.dto.OrganizationPageResponse;
import se.swedenconnect.iam.admin.controllers.dto.OrganizationResponse;
import se.swedenconnect.iam.admin.controllers.dto.UpdateFunctionRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.FunctionInfo;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;

import java.util.List;

/**
 * REST controller for function management operations.
 *
 * <p>Creating functions is restricted to superusers. Listing functions is available to all
 * authenticated users.</p>
 *
 * @author Martin Lindström
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class FunctionController {

  private final KeycloakAdminClient keycloakAdminClient;
  private final IamAdminProperties properties;

  /**
   * Lists all canonical function definitions.
   *
   * @return 200 OK with the list of functions
   */
  @GetMapping(value = "/functions", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<List<FunctionResponse>> getFunctions() {
    final List<FunctionInfo> functions = this.keycloakAdminClient.fetchAllFunctions();
    final List<FunctionResponse> response = functions.stream()
        .map(f -> new FunctionResponse(
            f.id(),
            f.name().get("sv"),
            f.name().get("en"),
            f.description() != null ? f.description().get("sv") : null,
            f.description() != null ? f.description().get("en") : null))
        .toList();
    return ResponseEntity.ok(response);
  }

  /**
   * Returns a paginated list of organizations that have the given function attached.
   *
   * <p>The full list of org identifiers is retrieved from Keycloak in one call (Keycloak group
   * search), then paginated in memory. Superusers see all organizations; regular admins see
   * only the organizations they administer.</p>
   *
   * @param functionId the function identifier
   * @param page       0-based page index (default 0)
   * @param size       page size (default 50)
   * @param request    the HTTP servlet request
   * @return 200 with paginated organizations, 403 if not authenticated
   */
  @GetMapping(value = "/functions/{functionId}/organizations", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<OrganizationPageResponse> getOrganizationsForFunction(
      @PathVariable final String functionId,
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "50") final int size,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null) {
      log.info("GET /api/functions/{}/organizations — rejected: no valid admin session", functionId);
      return ResponseEntity.status(403).build();
    }

    final int effectiveSize = size > 0 ? size : 50;
    final int effectivePage = Math.max(page, 0);
    final int first = effectivePage * effectiveSize;

    List<String> orgIds = this.keycloakAdminClient.fetchOrgIdentifiersForFunction(functionId);

    if (!data.currentUserIsSuperuser()) {
      final java.util.Set<String> adminOrgs = data.adminOrgIdentifiers();
      orgIds = orgIds.stream().filter(adminOrgs::contains).toList();
    }

    final int total = orgIds.size();
    final int fromIndex = Math.min(first, total);
    final int toIndex = Math.min(first + effectiveSize, total);
    final List<String> pageIds = orgIds.subList(fromIndex, toIndex);

    final List<OrganizationResponse> content = pageIds.stream()
        .map(id -> this.keycloakAdminClient.fetchOrganizationByIdentifier(id).orElse(null))
        .filter(java.util.Objects::nonNull)
        .map(FunctionController::toOrgResponse)
        .toList();

    final int totalPages = effectiveSize > 0 ? (int) Math.ceil((double) total / effectiveSize) : 0;
    log.debug("GET /api/functions/{}/organizations — page={}, size={}, total={}", functionId, effectivePage, effectiveSize, total);
    return ResponseEntity.ok(new OrganizationPageResponse(content, total, effectivePage, effectiveSize, totalPages));
  }

  private static OrganizationResponse toOrgResponse(final OrganizationInfo o) {
    return new OrganizationResponse(
        o.orgIdentifier(),
        o.name().get("sv"),
        o.name().get("en"),
        o.groupId(),
        o.attachedFunctions(),
        o.contactEmail(),
        o.contactPhone());
  }

  /**
   * Creates a new canonical function definition in Keycloak.
   *
   * @param req     the request body
   * @param request the HTTP servlet request
   * @return 201 Created with the created function, 400 on invalid input, 403 if not superuser,
   *         409 if function name already exists
   */
  @PostMapping(value = "/functions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> createFunction(
      @RequestBody final CreateFunctionRequest req,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null || !data.currentUserIsSuperuser()) {
      log.info("POST /api/functions — rejected: caller is not a superuser");
      return ResponseEntity.status(403).build();
    }

    final String name = req.name();
    if (!name.matches("^[a-z0-9_-]+$")) {
      log.info("POST /api/functions — rejected: invalid function name '{}'", name);
      return ResponseEntity.badRequest().body("name must match [a-z0-9_-]+");
    }
    if (req.nameSv().isBlank()) {
      return ResponseEntity.badRequest().body("nameSv must not be blank");
    }
    if (req.nameEn().isBlank()) {
      return ResponseEntity.badRequest().body("nameEn must not be blank");
    }

    if (this.keycloakAdminClient.functionExists(name)) {
      log.info("POST /api/functions — rejected: function '{}' already exists", name);
      return ResponseEntity.status(409).build();
    }

    try {
      this.keycloakAdminClient.createFunction(
          name, req.nameSv(), req.nameEn(), req.descriptionSv(), req.descriptionEn());
    }
    catch (final KeycloakAdminException e) {
      log.error("POST /api/functions — Keycloak error: {}", e.getMessage(), e);
      return ResponseEntity.status(500).body(e.getMessage());
    }

    log.info("POST /api/functions — function '{}' created successfully", name);
    return ResponseEntity.status(201).body(
        new FunctionResponse(name, req.nameSv(), req.nameEn(), req.descriptionSv(), req.descriptionEn()));
  }

  /**
   * Updates a function's display names and descriptions in Keycloak.
   *
   * <p>Only superusers may perform this operation. The function identifier is immutable.</p>
   *
   * @param functionId the function identifier
   * @param req        the request body
   * @param request    the HTTP servlet request
   * @return 200 OK with updated function data; 400 on validation error; 403 if not superuser;
   *         404 if function not found; 500 on Keycloak error
   */
  @PutMapping(value = "/functions/{functionId}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> updateFunction(
      @PathVariable final String functionId,
      @RequestBody final UpdateFunctionRequest req,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null || !data.currentUserIsSuperuser()) {
      log.info("PUT /api/functions/{} — rejected: caller is not a superuser", functionId);
      return ResponseEntity.status(403).build();
    }

    if (req.nameSv().isBlank()) {
      return ResponseEntity.badRequest().body("nameSv must not be blank");
    }
    if (req.nameEn().isBlank()) {
      return ResponseEntity.badRequest().body("nameEn must not be blank");
    }

    if (!this.keycloakAdminClient.functionExists(functionId)) {
      log.info("PUT /api/functions/{} — not found", functionId);
      return ResponseEntity.notFound().build();
    }

    try {
      this.keycloakAdminClient.updateFunction(
          functionId, req.nameSv(), req.nameEn(), req.descriptionSv(), req.descriptionEn());
    }
    catch (final KeycloakAdminException e) {
      log.error("PUT /api/functions/{} — Keycloak error: {}", functionId, e.getMessage(), e);
      return ResponseEntity.status(500).body(e.getMessage());
    }

    log.info("PUT /api/functions/{} — updated successfully", functionId);
    return ResponseEntity.ok(
        new FunctionResponse(functionId, req.nameSv(), req.nameEn(), req.descriptionSv(), req.descriptionEn()));
  }

  /**
   * Permanently deletes a function and all its Keycloak artifacts.
   *
   * <p>Only available when {@code iam.admin.allow-function-removal=true}. Restricted to
   * superusers.</p>
   *
   * @param functionId the function identifier
   * @param request    the HTTP servlet request
   * @return 204 No Content on success; 403 if not superuser or feature disabled;
   *         404 if function not found; 500 on Keycloak error
   */
  @DeleteMapping(value = "/functions/{functionId}")
  public ResponseEntity<?> deleteFunction(
      @PathVariable final String functionId,
      final HttpServletRequest request) {

    if (!this.properties.isAllowFunctionRemoval()) {
      log.info("DELETE /api/functions/{} — rejected: allow-function-removal is false", functionId);
      return ResponseEntity.status(403).build();
    }

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null || !data.currentUserIsSuperuser()) {
      log.info("DELETE /api/functions/{} — rejected: caller is not a superuser", functionId);
      return ResponseEntity.status(403).build();
    }

    if (!this.keycloakAdminClient.functionExists(functionId)) {
      log.info("DELETE /api/functions/{} — not found", functionId);
      return ResponseEntity.notFound().build();
    }

    try {
      this.keycloakAdminClient.deleteFunction(functionId);
    }
    catch (final KeycloakAdminException e) {
      log.error("DELETE /api/functions/{} — Keycloak error: {}", functionId, e.getMessage(), e);
      return ResponseEntity.status(500).body(e.getMessage());
    }

    log.info("DELETE /api/functions/{} — deleted successfully", functionId);
    return ResponseEntity.noContent().build();
  }

}
