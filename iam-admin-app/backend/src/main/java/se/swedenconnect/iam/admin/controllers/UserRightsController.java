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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
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
import se.swedenconnect.iam.admin.audit.AuditData;
import se.swedenconnect.iam.admin.audit.AuditOperations;
import se.swedenconnect.iam.admin.audit.IamAuditEvent;
import se.swedenconnect.iam.admin.audit.IamErrorAuditEvent;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.AddUserRightRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.UserRight;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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
  private final ApplicationEventPublisher eventPublisher;

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
      return this.denied(AuditOperations.ASSIGN_USER_RIGHT, AuditOperations.NO_ADMIN_SESSION, this.target(orgIdentifier, null, userId, req.right()));
    }

    if (!this.properties.isAllowOrgRights()) {
      log.info("PUT /api/organizations/{}/users/{}/rights — rejected: allow-org-rights is false",
          orgIdentifier, userId);
      return this.denied(AuditOperations.ASSIGN_USER_RIGHT, AuditOperations.ORG_RIGHTS_DISABLED, this.target(orgIdentifier, null, userId, req.right()));
    }

    final String currentUserId = getCurrentUserId();
    if (currentUserId == null) {
      return this.denied(AuditOperations.ASSIGN_USER_RIGHT, AuditOperations.NO_ADMIN_SESSION, this.target(orgIdentifier, null, userId, req.right()));
    }

    if (currentUserId.equals(userId)) {
      log.info("PUT /api/organizations/{}/users/{}/rights — rejected: self-assignment forbidden",
          orgIdentifier, userId);
      return this.denied(AuditOperations.ASSIGN_USER_RIGHT, AuditOperations.SELF_MODIFICATION, this.target(orgIdentifier, null, userId, req.right()));
    }

    if (!data.currentUserIsSuperuser() && !hasOrgAdminRight(data, orgIdentifier)) {
      log.info("PUT /api/organizations/{}/users/{}/rights — rejected: caller lacks org-admin right",
          orgIdentifier, userId);
      return this.denied(AuditOperations.ASSIGN_USER_RIGHT, AuditOperations.INSUFFICIENT_RIGHTS, this.target(orgIdentifier, null, userId, req.right()));
    }

    final String right = req.right();
    if (right == null || !VALID_RIGHTS.contains(right)) {
      return ResponseEntity.badRequest().body("right must be one of: read, write, admin");
    }

    final boolean adminRightRestricted = adminRightRestricted(data);
    final List<UserRight> currentRights = this.keycloakAdminClient.fetchUserRights(userId);

    if ("admin".equals(right)) {
      if (adminRightRestricted) {
        log.info("PUT /api/organizations/{}/users/{}/rights — rejected: allow-admin-assigning-admin is false",
            orgIdentifier, userId);
        return this.denied(AuditOperations.ASSIGN_USER_RIGHT, AuditOperations.ADMIN_ASSIGNMENT_RESTRICTED, this.target(orgIdentifier, null, userId, req.right()));
      }
    }
    else {
      final boolean currentlyOrgAdmin = currentRights.stream()
          .anyMatch(r -> orgIdentifier.equals(r.orgIdentifier())
              && r.functionId() == null
              && "admin".equals(r.right()));
      if (currentlyOrgAdmin && adminRightRestricted) {
        log.info("PUT /api/organizations/{}/users/{}/rights — rejected: allow-admin-assigning-admin is false",
            orgIdentifier, userId);
        return this.denied(AuditOperations.ASSIGN_USER_RIGHT, AuditOperations.ADMIN_ASSIGNMENT_RESTRICTED, this.target(orgIdentifier, null, userId, req.right()));
      }
      if (currentlyOrgAdmin && !this.keycloakAdminClient.hasOtherOrgAdmin(orgIdentifier, userId)) {
        log.info("PUT /api/organizations/{}/users/{}/rights — rejected: last admin for org '{}'",
            orgIdentifier, userId, orgIdentifier);
        this.eventPublisher.publishEvent(IamAuditEvent.operationRejected(
            new AuditData.Operation(AuditOperations.ASSIGN_USER_RIGHT, AuditOperations.LAST_ADMIN), this.target(orgIdentifier, null, userId, req.right())));
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
    }
    catch (final KeycloakAdminException e) {
      this.eventPublisher.publishEvent(IamErrorAuditEvent.operationFailed(
          new AuditData.Operation(AuditOperations.ASSIGN_USER_RIGHT, null),
          this.target(orgIdentifier, null, userId, right), e));
      log.error("PUT /api/organizations/{}/users/{}/rights — Keycloak error: {}",
          orgIdentifier, userId, e.getMessage());
      return ResponseEntity.status(500).body(e.getMessage());
    }
    this.auditAssigned(userId, orgIdentifier, null, right, currentRights);
    return ResponseEntity.noContent().build();
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
      return this.denied(AuditOperations.ASSIGN_USER_RIGHT, AuditOperations.NO_ADMIN_SESSION, this.target(orgIdentifier, functionId, userId, req.right()));
    }

    final String currentUserId = getCurrentUserId();
    if (currentUserId == null) {
      return this.denied(AuditOperations.ASSIGN_USER_RIGHT, AuditOperations.NO_ADMIN_SESSION, this.target(orgIdentifier, functionId, userId, req.right()));
    }

    if (currentUserId.equals(userId)) {
      log.info("PUT /api/organizations/{}/functions/{}/users/{}/rights — rejected: self-assignment forbidden",
          orgIdentifier, functionId, userId);
      return this.denied(AuditOperations.ASSIGN_USER_RIGHT, AuditOperations.SELF_MODIFICATION, this.target(orgIdentifier, functionId, userId, req.right()));
    }

    if (!data.currentUserIsSuperuser() && !hasFunctionAdminRight(data, orgIdentifier, functionId)) {
      log.info("PUT /api/organizations/{}/functions/{}/users/{}/rights — rejected: caller lacks function-admin right",
          orgIdentifier, functionId, userId);
      return this.denied(AuditOperations.ASSIGN_USER_RIGHT, AuditOperations.INSUFFICIENT_RIGHTS, this.target(orgIdentifier, functionId, userId, req.right()));
    }

    final String right = req.right();
    if (right == null || !VALID_RIGHTS.contains(right)) {
      return ResponseEntity.badRequest().body("right must be one of: read, write, admin");
    }

    final boolean adminRightRestricted = adminRightRestricted(data);
    final List<UserRight> currentRights = this.keycloakAdminClient.fetchUserRights(userId);

    if ("admin".equals(right)) {
      if (adminRightRestricted) {
        log.info("PUT /api/organizations/{}/functions/{}/users/{}/rights "
                + "— rejected: allow-admin-assigning-admin is false",
            orgIdentifier, functionId, userId);
        return this.denied(AuditOperations.ASSIGN_USER_RIGHT, AuditOperations.ADMIN_ASSIGNMENT_RESTRICTED, this.target(orgIdentifier, functionId, userId, req.right()));
      }
    }
    else {
      final boolean currentlyFunctionAdmin = currentRights.stream()
          .anyMatch(r -> orgIdentifier.equals(r.orgIdentifier())
              && functionId.equals(r.functionId())
              && "admin".equals(r.right()));
      if (currentlyFunctionAdmin && adminRightRestricted) {
        log.info("PUT /api/organizations/{}/functions/{}/users/{}/rights "
                + "— rejected: allow-admin-assigning-admin is false",
            orgIdentifier, functionId, userId);
        return this.denied(AuditOperations.ASSIGN_USER_RIGHT, AuditOperations.ADMIN_ASSIGNMENT_RESTRICTED, this.target(orgIdentifier, functionId, userId, req.right()));
      }
      if (currentlyFunctionAdmin && !this.keycloakAdminClient.hasOtherFunctionAdmin(orgIdentifier, functionId, userId)) {
        log.info("PUT /api/organizations/{}/functions/{}/users/{}/rights — rejected: last admin for function '{}/{}'",
            orgIdentifier, functionId, userId, orgIdentifier, functionId);
        this.eventPublisher.publishEvent(IamAuditEvent.operationRejected(
            new AuditData.Operation(AuditOperations.ASSIGN_USER_RIGHT, AuditOperations.LAST_ADMIN), this.target(orgIdentifier, functionId, userId, req.right())));
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
    }
    catch (final KeycloakAdminException e) {
      log.error("PUT /api/organizations/{}/functions/{}/users/{}/rights — Keycloak error: {}",
          orgIdentifier, functionId, userId, e.getMessage());
      this.eventPublisher.publishEvent(IamErrorAuditEvent.operationFailed(
          new AuditData.Operation(AuditOperations.ASSIGN_USER_RIGHT, null),
          this.target(orgIdentifier, functionId, userId, right), e));
      return ResponseEntity.status(500).body(e.getMessage());
    }
    this.auditAssigned(userId, orgIdentifier, functionId, right, currentRights);
    return ResponseEntity.noContent().build();
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
      return this.denied(AuditOperations.REMOVE_USER_RIGHT, AuditOperations.NO_ADMIN_SESSION, this.target(orgIdentifier, null, userId, right));
    }

    final String currentUserId = getCurrentUserId();
    if (currentUserId == null) {
      return this.denied(AuditOperations.REMOVE_USER_RIGHT, AuditOperations.NO_ADMIN_SESSION, this.target(orgIdentifier, null, userId, right));
    }

    if (currentUserId.equals(userId)) {
      log.info("DELETE /api/organizations/{}/users/{}/rights — rejected: self-removal forbidden",
          orgIdentifier, userId);
      return this.denied(AuditOperations.REMOVE_USER_RIGHT, AuditOperations.SELF_MODIFICATION, this.target(orgIdentifier, null, userId, right));
    }

    if (!data.currentUserIsSuperuser() && !hasOrgAdminRight(data, orgIdentifier)) {
      log.info("DELETE /api/organizations/{}/users/{}/rights — rejected: caller lacks org-admin right",
          orgIdentifier, userId);
      return this.denied(AuditOperations.REMOVE_USER_RIGHT, AuditOperations.INSUFFICIENT_RIGHTS, this.target(orgIdentifier, null, userId, right));
    }

    if (!VALID_RIGHTS.contains(right)) {
      return ResponseEntity.badRequest().body("right must be one of: read, write, admin");
    }

    if ("admin".equals(right) && adminRightRestricted(data)) {
      log.info("DELETE /api/organizations/{}/users/{}/rights — rejected: allow-admin-assigning-admin is false",
          orgIdentifier, userId);
      return this.denied(AuditOperations.REMOVE_USER_RIGHT, AuditOperations.ADMIN_ASSIGNMENT_RESTRICTED, this.target(orgIdentifier, null, userId, right));
    }

    if ("admin".equals(right) && !this.keycloakAdminClient.hasOtherOrgAdmin(orgIdentifier, userId)) {
      log.info("DELETE /api/organizations/{}/users/{}/rights — rejected: last admin for org '{}'",
          orgIdentifier, userId, orgIdentifier);
      this.eventPublisher.publishEvent(IamAuditEvent.operationRejected(
          new AuditData.Operation(AuditOperations.REMOVE_USER_RIGHT, AuditOperations.LAST_ADMIN), this.target(orgIdentifier, null, userId, right)));
      return ResponseEntity.status(409).body(Map.of("reason", "LAST_ADMIN", "scope", orgIdentifier));
    }

    final boolean held = this.holdsRight(userId, orgIdentifier, null, right);
    try {
      this.keycloakAdminClient.removeUserFromOrgRight(orgIdentifier, userId, right);
      log.info("DELETE /api/organizations/{}/users/{}/rights — removed right '{}'",
          orgIdentifier, userId, right);
    }
    catch (final KeycloakAdminException e) {
      log.error("DELETE /api/organizations/{}/users/{}/rights — Keycloak error: {}",
          orgIdentifier, userId, e.getMessage());
      this.eventPublisher.publishEvent(IamErrorAuditEvent.operationFailed(
          new AuditData.Operation(AuditOperations.REMOVE_USER_RIGHT, null),
          this.target(orgIdentifier, null, userId, right), e));
      return ResponseEntity.status(500).body(e.getMessage());
    }
    if (held) {
      this.eventPublisher.publishEvent(
          IamAuditEvent.userRightRemoved(this.auditUser(userId), orgIdentifier, null, right));
    }
    return ResponseEntity.noContent().build();
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
      return this.denied(AuditOperations.REMOVE_USER_RIGHT, AuditOperations.NO_ADMIN_SESSION, this.target(orgIdentifier, functionId, userId, right));
    }

    final String currentUserId = getCurrentUserId();
    if (currentUserId == null) {
      return this.denied(AuditOperations.REMOVE_USER_RIGHT, AuditOperations.NO_ADMIN_SESSION, this.target(orgIdentifier, functionId, userId, right));
    }

    if (currentUserId.equals(userId)) {
      log.info("DELETE /api/organizations/{}/functions/{}/users/{}/rights — rejected: self-removal forbidden",
          orgIdentifier, functionId, userId);
      return this.denied(AuditOperations.REMOVE_USER_RIGHT, AuditOperations.SELF_MODIFICATION, this.target(orgIdentifier, functionId, userId, right));
    }

    if (!data.currentUserIsSuperuser() && !hasFunctionAdminRight(data, orgIdentifier, functionId)) {
      log.info("DELETE /api/organizations/{}/functions/{}/users/{}/rights — rejected: caller lacks function-admin right",
          orgIdentifier, functionId, userId);
      return this.denied(AuditOperations.REMOVE_USER_RIGHT, AuditOperations.INSUFFICIENT_RIGHTS, this.target(orgIdentifier, functionId, userId, right));
    }

    if (!VALID_RIGHTS.contains(right)) {
      return ResponseEntity.badRequest().body("right must be one of: read, write, admin");
    }

    if ("admin".equals(right) && adminRightRestricted(data)) {
      log.info("DELETE /api/organizations/{}/functions/{}/users/{}/rights "
              + "— rejected: allow-admin-assigning-admin is false",
          orgIdentifier, functionId, userId);
      return this.denied(AuditOperations.REMOVE_USER_RIGHT, AuditOperations.ADMIN_ASSIGNMENT_RESTRICTED, this.target(orgIdentifier, functionId, userId, right));
    }

    if ("admin".equals(right) && !this.keycloakAdminClient.hasOtherFunctionAdmin(orgIdentifier, functionId, userId)) {
      log.info("DELETE /api/organizations/{}/functions/{}/users/{}/rights — rejected: last admin for function '{}/{}'",
          orgIdentifier, functionId, userId, orgIdentifier, functionId);
      this.eventPublisher.publishEvent(IamAuditEvent.operationRejected(
          new AuditData.Operation(AuditOperations.REMOVE_USER_RIGHT, AuditOperations.LAST_ADMIN), this.target(orgIdentifier, functionId, userId, right)));
      return ResponseEntity.status(409).body(
          Map.of("reason", "LAST_ADMIN", "scope", orgIdentifier + "/" + functionId));
    }

    final boolean held = this.holdsRight(userId, orgIdentifier, functionId, right);
    try {
      this.keycloakAdminClient.removeUserFromFunctionRight(orgIdentifier, functionId, userId, right);
      log.info("DELETE /api/organizations/{}/functions/{}/users/{}/rights — removed right '{}'",
          orgIdentifier, functionId, userId, right);
    }
    catch (final KeycloakAdminException e) {
      log.error("DELETE /api/organizations/{}/functions/{}/users/{}/rights — Keycloak error: {}",
          orgIdentifier, functionId, userId, e.getMessage());
      this.eventPublisher.publishEvent(IamErrorAuditEvent.operationFailed(
          new AuditData.Operation(AuditOperations.REMOVE_USER_RIGHT, null),
          this.target(orgIdentifier, functionId, userId, right), e));
      return ResponseEntity.status(500).body(e.getMessage());
    }
    if (held) {
      this.eventPublisher.publishEvent(
          IamAuditEvent.userRightRemoved(this.auditUser(userId), orgIdentifier, functionId, right));
    }
    return ResponseEntity.noContent().build();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * True if the caller may not touch the {@code admin} right at all — that is, the caller is not a
   * superuser and {@code iam.admin.allow-admin-assigning-admin} is {@code false}. Such a caller is
   * limited to {@code read} and {@code write}: they can neither grant nor remove {@code admin}, nor
   * downgrade an existing admin to a lower right.
   */
  private boolean adminRightRestricted(final AdminSessionData data) {
    return !data.currentUserIsSuperuser() && !this.properties.isAllowAdminAssigningAdmin();
  }

  /**
   * Records a {@code user_right_assigned} audit event, unless the user already held the right.
   *
   * @param userId        the user
   * @param orgIdentifier the organization
   * @param functionId    the function, or {@code null} for an organization-wide right
   * @param right         the right granted
   * @param before        the user's rights before the grant
   */
  private void auditAssigned(final @NonNull String userId, final @NonNull String orgIdentifier,
      final @Nullable String functionId, final @NonNull String right, final @NonNull List<UserRight> before) {
    final String previous = before.stream()
        .filter(r -> orgIdentifier.equals(r.orgIdentifier()) && Objects.equals(functionId, r.functionId()))
        .map(UserRight::right)
        .findFirst()
        .orElse(null);
    if (!right.equals(previous)) {
      this.eventPublisher.publishEvent(IamAuditEvent.userRightAssigned(
          this.auditUser(userId), orgIdentifier, functionId, right, previous, null));
    }
  }

  /**
   * Tells whether the user holds the given right.
   *
   * @param userId        the user
   * @param orgIdentifier the organization
   * @param functionId    the function, or {@code null} for an organization-wide right
   * @param right         the right level
   * @return {@code true} if the user holds the right
   */
  private boolean holdsRight(final @NonNull String userId, final @NonNull String orgIdentifier,
      final @Nullable String functionId, final @NonNull String right) {
    return this.keycloakAdminClient.fetchUserRights(userId).stream()
        .anyMatch(r -> orgIdentifier.equals(r.orgIdentifier())
            && Objects.equals(functionId, r.functionId())
            && right.equals(r.right()));
  }

  /**
   * Records an {@code access_denied} audit event and returns the 403 response.
   *
   * @param operation the operation refused
   * @param reason    the reason
   * @param target    what the operation was aimed at
   * @return a 403 response
   */
  private ResponseEntity<?> denied(
      final @NonNull String operation, final @NonNull String reason, final AuditData.@NonNull Target target) {
    this.eventPublisher.publishEvent(
        IamAuditEvent.accessDenied(new AuditData.Operation(operation, reason), target));
    return ResponseEntity.status(403).build();
  }

  /**
   * Describes the target of a rights operation for the audit log.
   *
   * @param orgIdentifier the organization
   * @param functionId    the function, or {@code null}
   * @param userId        the user
   * @param right         the right level, or {@code null}
   * @return the target
   */
  private AuditData.@NonNull Target target(final @NonNull String orgIdentifier, final @Nullable String functionId,
      final @NonNull String userId, final @Nullable String right) {
    return AuditData.Target.none()
        .user(this.auditUser(userId))
        .organization(orgIdentifier)
        .function(functionId)
        .right(right);
  }

  /**
   * Identifies a user for the audit log, with the name if the user can be read.
   *
   * @param userId the Keycloak user UUID
   * @return the user object
   */
  private AuditData.@NonNull User auditUser(final @NonNull String userId) {
    try {
      return this.keycloakAdminClient.fetchUserById(userId)
          .map(AuditData.User::of)
          .orElseGet(() -> AuditData.User.of(userId, null));
    }
    catch (final RuntimeException e) {
      return AuditData.User.of(userId, null);
    }
  }

  private static String getCurrentUserId() {
    final var auth = SecurityContextHolder.getContext().getAuthentication();
    return (auth != null && auth.getPrincipal() instanceof final OidcUser oidc)
        ? oidc.getSubject()
        : null;
  }

  /**
   * True if the caller holds {@code admin} at the <em>organization</em> level for the given
   * organization — the stricter right required to grant or revoke org-level rights. This is the
   * one legitimate use of {@code org_level_right}: it answers "may this person administer the
   * organization itself", not "what may they do".
   */
  private static boolean hasOrgAdminRight(
      final AdminSessionData data,
      final String orgIdentifier) {
    return data.hasOrgLevelAdminRight(orgIdentifier);
  }

  private static boolean hasFunctionAdminRight(
      final AdminSessionData data,
      final String orgIdentifier,
      final String functionId) {
    return data.hasFunctionAdminRight(orgIdentifier, functionId);
  }


}
