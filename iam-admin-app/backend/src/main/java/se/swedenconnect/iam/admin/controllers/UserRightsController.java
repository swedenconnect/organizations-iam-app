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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.AddUserRightRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.UserRight;

import java.util.Map;
import java.util.Set;

/**
 * REST controller for assigning user rights within organizations and functions.
 *
 * <p>Superusers may assign rights in any org or function. Org-admins may assign within
 * their own org. Function-admins may assign within their specific function only.</p>
 *
 * @author Martin Lindström
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class UserRightsController {

  private static final Set<String> VALID_RIGHTS = Set.of("read", "write", "admin");

  private final KeycloakAdminClient keycloakAdminClient;
  private final IamAdminProperties properties;

  /**
   * Adds a user to an organization with the given right (org-level).
   *
   * @param orgIdentifier the organization identifier
   * @param userId        the Keycloak user UUID to add
   * @param req           the request body containing the right level
   * @param request       the HTTP servlet request
   * @return 204 on success, 400/403/404/500 on error
   */
  @PutMapping("/organizations/{orgIdentifier}/users/{userId}/rights")
  public ResponseEntity<?> addUserToOrg(
      @PathVariable final String orgIdentifier,
      @PathVariable final String userId,
      @RequestBody final AddUserRightRequest req,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null) {
      log.info("PUT /api/organizations/{}/users/{}/rights — rejected: no valid admin session",
          orgIdentifier, userId);
      return ResponseEntity.status(403).build();
    }

    if (!this.properties.isAllowOrgRights()) {
      log.info("PUT /api/organizations/{}/users/{}/rights — rejected: allow-org-rights is false",
          orgIdentifier, userId);
      return ResponseEntity.status(403).build();
    }

    final String currentUserId = getCurrentUserId();
    if (currentUserId == null) {
      return ResponseEntity.status(403).build();
    }

    if (currentUserId.equals(userId)) {
      log.info("PUT /api/organizations/{}/users/{}/rights — rejected: self-assignment forbidden",
          orgIdentifier, userId);
      return ResponseEntity.status(403).build();
    }

    if (!data.currentUserIsSuperuser() && !hasOrgAdminRight(data, orgIdentifier)) {
      log.info("PUT /api/organizations/{}/users/{}/rights — rejected: caller lacks org-admin right",
          orgIdentifier, userId);
      return ResponseEntity.status(403).build();
    }

    final String right = req.right();
    if (right == null || !VALID_RIGHTS.contains(right)) {
      return ResponseEntity.badRequest().body("right must be one of: read, write, admin");
    }

    if (!"admin".equals(right)) {
      final boolean currentlyOrgAdmin = this.keycloakAdminClient.fetchUserRights(userId).stream()
          .anyMatch(r -> orgIdentifier.equals(r.orgIdentifier())
              && r.functionId() == null
              && "admin".equals(r.right()));
      if (currentlyOrgAdmin && !this.keycloakAdminClient.hasOtherOrgAdmin(orgIdentifier, userId)) {
        log.info("PUT /api/organizations/{}/users/{}/rights — rejected: last admin for org '{}'",
            orgIdentifier, userId, orgIdentifier);
        return ResponseEntity.status(409).body(Map.of("reason", "LAST_ADMIN", "scope", orgIdentifier));
      }
    }

    try {
      if (!this.keycloakAdminClient.organizationExists(orgIdentifier)) {
        return ResponseEntity.notFound().build();
      }
      this.keycloakAdminClient.addUserToOrgRight(orgIdentifier, userId, right);
      log.info("PUT /api/organizations/{}/users/{}/rights — user added with right '{}'",
          orgIdentifier, userId, right);
      return ResponseEntity.noContent().build();
    }
    catch (final KeycloakAdminException e) {
      log.error("PUT /api/organizations/{}/users/{}/rights — Keycloak error: {}",
          orgIdentifier, userId, e.getMessage());
      return ResponseEntity.status(500).body(e.getMessage());
    }
  }

  /**
   * Adds a user to a specific function within an organization with the given right.
   *
   * @param orgIdentifier the organization identifier
   * @param functionId    the function identifier
   * @param userId        the Keycloak user UUID to add
   * @param req           the request body containing the right level
   * @param request       the HTTP servlet request
   * @return 204 on success, 400/403/404/500 on error
   */
  @PutMapping("/organizations/{orgIdentifier}/functions/{functionId}/users/{userId}/rights")
  public ResponseEntity<?> addUserToFunction(
      @PathVariable final String orgIdentifier,
      @PathVariable final String functionId,
      @PathVariable final String userId,
      @RequestBody final AddUserRightRequest req,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null) {
      log.info("PUT /api/organizations/{}/functions/{}/users/{}/rights — rejected: no valid admin session",
          orgIdentifier, functionId, userId);
      return ResponseEntity.status(403).build();
    }

    final String currentUserId = getCurrentUserId();
    if (currentUserId == null) {
      return ResponseEntity.status(403).build();
    }

    if (currentUserId.equals(userId)) {
      log.info("PUT /api/organizations/{}/functions/{}/users/{}/rights — rejected: self-assignment forbidden",
          orgIdentifier, functionId, userId);
      return ResponseEntity.status(403).build();
    }

    if (!data.currentUserIsSuperuser() && !hasFunctionAdminRight(data, orgIdentifier, functionId)) {
      log.info("PUT /api/organizations/{}/functions/{}/users/{}/rights — rejected: caller lacks function-admin right",
          orgIdentifier, functionId, userId);
      return ResponseEntity.status(403).build();
    }

    final String right = req.right();
    if (right == null || !VALID_RIGHTS.contains(right)) {
      return ResponseEntity.badRequest().body("right must be one of: read, write, admin");
    }

    if (!"admin".equals(right)) {
      final boolean currentlyFunctionAdmin = this.keycloakAdminClient.fetchUserRights(userId).stream()
          .anyMatch(r -> orgIdentifier.equals(r.orgIdentifier())
              && functionId.equals(r.functionId())
              && "admin".equals(r.right()));
      if (currentlyFunctionAdmin && !this.keycloakAdminClient.hasOtherFunctionAdmin(orgIdentifier, functionId, userId)) {
        log.info("PUT /api/organizations/{}/functions/{}/users/{}/rights — rejected: last admin for function '{}/{}'",
            orgIdentifier, functionId, userId, orgIdentifier, functionId);
        return ResponseEntity.status(409).body(
            Map.of("reason", "LAST_ADMIN", "scope", orgIdentifier + "/" + functionId));
      }
    }

    try {
      if (!this.keycloakAdminClient.organizationExists(orgIdentifier)) {
        return ResponseEntity.notFound().build();
      }
      if (!this.keycloakAdminClient.isFunctionAttachedToOrg(orgIdentifier, functionId)) {
        return ResponseEntity.notFound().build();
      }
      this.keycloakAdminClient.addUserToFunctionRight(orgIdentifier, functionId, userId, right);
      log.info("PUT /api/organizations/{}/functions/{}/users/{}/rights — user added with right '{}'",
          orgIdentifier, functionId, userId, right);
      return ResponseEntity.noContent().build();
    }
    catch (final KeycloakAdminException e) {
      log.error("PUT /api/organizations/{}/functions/{}/users/{}/rights — Keycloak error: {}",
          orgIdentifier, functionId, userId, e.getMessage());
      return ResponseEntity.status(500).body(e.getMessage());
    }
  }

  /**
   * Removes a user's right from an organization (org-level).
   *
   * @param orgIdentifier the organization identifier
   * @param userId        the Keycloak user UUID to remove
   * @param right         the right level to remove ({@code read}, {@code write}, or {@code admin})
   * @param request       the HTTP servlet request
   * @return 204 on success, 400/403/500 on error
   */
  @DeleteMapping("/organizations/{orgIdentifier}/users/{userId}/rights")
  public ResponseEntity<?> removeUserFromOrg(
      @PathVariable final String orgIdentifier,
      @PathVariable final String userId,
      @RequestParam final String right,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null) {
      log.info("DELETE /api/organizations/{}/users/{}/rights — rejected: no valid admin session",
          orgIdentifier, userId);
      return ResponseEntity.status(403).build();
    }

    final String currentUserId = getCurrentUserId();
    if (currentUserId == null) {
      return ResponseEntity.status(403).build();
    }

    if (currentUserId.equals(userId)) {
      log.info("DELETE /api/organizations/{}/users/{}/rights — rejected: self-removal forbidden",
          orgIdentifier, userId);
      return ResponseEntity.status(403).build();
    }

    if (!data.currentUserIsSuperuser() && !hasOrgAdminRight(data, orgIdentifier)) {
      log.info("DELETE /api/organizations/{}/users/{}/rights — rejected: caller lacks org-admin right",
          orgIdentifier, userId);
      return ResponseEntity.status(403).build();
    }

    if (!VALID_RIGHTS.contains(right)) {
      return ResponseEntity.badRequest().body("right must be one of: read, write, admin");
    }

    if ("admin".equals(right) && !this.keycloakAdminClient.hasOtherOrgAdmin(orgIdentifier, userId)) {
      log.info("DELETE /api/organizations/{}/users/{}/rights — rejected: last admin for org '{}'",
          orgIdentifier, userId, orgIdentifier);
      return ResponseEntity.status(409).body(Map.of("reason", "LAST_ADMIN", "scope", orgIdentifier));
    }

    try {
      this.keycloakAdminClient.removeUserFromOrgRight(orgIdentifier, userId, right);
      log.info("DELETE /api/organizations/{}/users/{}/rights — removed right '{}'",
          orgIdentifier, userId, right);
      return ResponseEntity.noContent().build();
    }
    catch (final KeycloakAdminException e) {
      log.error("DELETE /api/organizations/{}/users/{}/rights — Keycloak error: {}",
          orgIdentifier, userId, e.getMessage());
      return ResponseEntity.status(500).body(e.getMessage());
    }
  }

  /**
   * Removes a user's right from a specific function within an organization.
   *
   * @param orgIdentifier the organization identifier
   * @param functionId    the function identifier
   * @param userId        the Keycloak user UUID to remove
   * @param right         the right level to remove ({@code read}, {@code write}, or {@code admin})
   * @param request       the HTTP servlet request
   * @return 204 on success, 400/403/500 on error
   */
  @DeleteMapping("/organizations/{orgIdentifier}/functions/{functionId}/users/{userId}/rights")
  public ResponseEntity<?> removeUserFromFunction(
      @PathVariable final String orgIdentifier,
      @PathVariable final String functionId,
      @PathVariable final String userId,
      @RequestParam final String right,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null) {
      log.info("DELETE /api/organizations/{}/functions/{}/users/{}/rights — rejected: no valid admin session",
          orgIdentifier, functionId, userId);
      return ResponseEntity.status(403).build();
    }

    final String currentUserId = getCurrentUserId();
    if (currentUserId == null) {
      return ResponseEntity.status(403).build();
    }

    if (currentUserId.equals(userId)) {
      log.info("DELETE /api/organizations/{}/functions/{}/users/{}/rights — rejected: self-removal forbidden",
          orgIdentifier, functionId, userId);
      return ResponseEntity.status(403).build();
    }

    if (!data.currentUserIsSuperuser() && !hasFunctionAdminRight(data, orgIdentifier, functionId)) {
      log.info("DELETE /api/organizations/{}/functions/{}/users/{}/rights — rejected: caller lacks function-admin right",
          orgIdentifier, functionId, userId);
      return ResponseEntity.status(403).build();
    }

    if (!VALID_RIGHTS.contains(right)) {
      return ResponseEntity.badRequest().body("right must be one of: read, write, admin");
    }

    if ("admin".equals(right) && !this.keycloakAdminClient.hasOtherFunctionAdmin(orgIdentifier, functionId, userId)) {
      log.info("DELETE /api/organizations/{}/functions/{}/users/{}/rights — rejected: last admin for function '{}/{}'",
          orgIdentifier, functionId, userId, orgIdentifier, functionId);
      return ResponseEntity.status(409).body(
          Map.of("reason", "LAST_ADMIN", "scope", orgIdentifier + "/" + functionId));
    }

    try {
      this.keycloakAdminClient.removeUserFromFunctionRight(orgIdentifier, functionId, userId, right);
      log.info("DELETE /api/organizations/{}/functions/{}/users/{}/rights — removed right '{}'",
          orgIdentifier, functionId, userId, right);
      return ResponseEntity.noContent().build();
    }
    catch (final KeycloakAdminException e) {
      log.error("DELETE /api/organizations/{}/functions/{}/users/{}/rights — Keycloak error: {}",
          orgIdentifier, functionId, userId, e.getMessage());
      return ResponseEntity.status(500).body(e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static String getCurrentUserId() {
    final var auth = SecurityContextHolder.getContext().getAuthentication();
    return (auth != null && auth.getPrincipal() instanceof final OidcUser oidc)
        ? oidc.getSubject()
        : null;
  }

  private static boolean hasOrgAdminRight(
      final AdminSessionData data,
      final String orgIdentifier) {
    return data.claim().orgEntries().stream()
        .filter(e -> orgIdentifier.equals(e.orgIdentifier().toString()))
        .flatMap(e -> e.functions().stream())
        .anyMatch(f -> "*".equals(f.function()) && "admin".equals(f.right()));
  }

  private static boolean hasFunctionAdminRight(
      final AdminSessionData data,
      final String orgIdentifier,
      final String functionId) {
    return data.claim().orgEntries().stream()
        .filter(e -> orgIdentifier.equals(e.orgIdentifier().toString()))
        .flatMap(e -> e.functions().stream())
        .anyMatch(f -> ("*".equals(f.function()) || functionId.equals(f.function()))
            && "admin".equals(f.right()));
  }


}
