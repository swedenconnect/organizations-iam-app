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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
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
import se.swedenconnect.iam.admin.controllers.dto.CreateUserRequest;
import se.swedenconnect.iam.admin.controllers.dto.UpdateUserRequest;
import se.swedenconnect.iam.admin.controllers.dto.UserPageResponse;
import se.swedenconnect.iam.admin.controllers.dto.UserResponse;
import se.swedenconnect.iam.admin.controllers.dto.UserRightResponse;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.UserInfo;
import se.swedenconnect.iam.admin.keycloak.model.UserRight;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * REST controller for user management operations.
 *
 * <p>Any authenticated admin (superuser or org-admin) may create users. A newly created
 * user has no rights — rights are assigned separately via group-membership endpoints.</p>
 *
 * @author Martin Lindström
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class UserController {

  private final KeycloakAdminClient keycloakAdminClient;

  private final IamAdminProperties properties;

  private static final int DEFAULT_PAGE_SIZE = 50;

  /** Pattern for an organizational affiliation: a local part, a single '@' and 10 digits. */
  private static final Pattern ORG_AFFILIATION_PATTERN = Pattern.compile("^[^@\\s]+@\\d{10}$");

  /**
   * Returns a paginated list of users.
   *
   * <p>Superusers receive all users paginated directly from Keycloak, with rights fetched
   * per user. Regular admins receive only their scoped users from session data with
   * in-memory pagination.</p>
   *
   * @param page    0-based page index (default 0)
   * @param size    page size (default 50)
   * @param request the HTTP servlet request
   * @return 200 with paginated users, 403 if not authenticated
   */
  @GetMapping(value = "/users", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<UserPageResponse> listUsers(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) final int size,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null) {
      log.info("GET /api/users — rejected: no valid admin session");
      return ResponseEntity.status(403).build();
    }

    final int effectiveSize = size > 0 ? size : DEFAULT_PAGE_SIZE;
    final int effectivePage = Math.max(page, 0);
    final int first = effectivePage * effectiveSize;

    final List<UserResponse> content;
    final int total;

    if (data.currentUserIsSuperuser()) {
      final List<UserInfo> users = this.keycloakAdminClient.fetchUsersPaged(first, effectiveSize);
      content = users.stream()
          .map(u -> toResponse(u, this.keycloakAdminClient.fetchUserRights(u.userId())))
          .toList();
      total = this.keycloakAdminClient.fetchUserCount();
      log.debug("GET /api/users — superuser, page={}, size={}, total={}", effectivePage, effectiveSize, total);
    }
    else {
      final List<String> userIds = this.keycloakAdminClient.fetchUserIdsForOrgs(data.adminOrgIdentifiers());
      // Apply orgConstraint if present
      final List<String> scopedIds;
      if (data.orgConstraint() != null) {
        final String oc = data.orgConstraint();
        scopedIds = userIds.stream()
            .filter(id -> this.keycloakAdminClient.fetchUserRights(id).stream()
                .anyMatch(r -> oc.equals(r.orgIdentifier())))
            .toList();
      }
      else {
        scopedIds = userIds;
      }
      total = scopedIds.size();
      final int fromIndex = Math.min(first, total);
      final int toIndex = Math.min(first + effectiveSize, total);
      content = scopedIds.subList(fromIndex, toIndex).stream()
          .map(id -> {
            final UserInfo u = this.keycloakAdminClient.fetchUserById(id).orElse(null);
            if (u == null) return null;
            List<UserRight> rights = this.keycloakAdminClient.fetchUserRights(id);
            if (data.orgConstraint() != null) {
              final String oc = data.orgConstraint();
              rights = rights.stream().filter(r -> oc.equals(r.orgIdentifier())).toList();
            }
            if (data.functionConstraint() != null) {
              final String fc = data.functionConstraint();
              rights = rights.stream()
                  .filter(r -> r.functionId() == null || fc.equals(r.functionId()))
                  .toList();
            }
            return toResponse(u, rights);
          })
          .filter(Objects::nonNull)
          .toList();
      log.debug("GET /api/users — regular admin, page={}, size={}, total={}", effectivePage, effectiveSize, total);
    }

    final int totalPages = effectiveSize > 0 ? (int) Math.ceil((double) total / effectiveSize) : 0;
    return ResponseEntity.ok(new UserPageResponse(content, total, effectivePage, effectiveSize, totalPages));
  }

  /**
   * Creates a new user in Keycloak.
   *
   * <p>What the request may carry is decided by {@code iam.admin.user-registration}. A value for
   * a setting that is turned off is ignored: the user ID unless {@code allow-select-user-id} is
   * set, the temporary password unless {@code allow-temporary-password} is set, and an eID
   * attribute unless the setting enabling it is set.</p>
   *
   * @param req     the request body
   * @param request the HTTP servlet request
   * @return 201 Created with the created user, 400 on invalid input, 403 if not authenticated,
   *         409 if the user ID is taken, or if a user with the same personal identity number or
   *         organizational affiliation already exists
   */
  @PostMapping(value = "/users",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> createUser(
      @RequestBody final CreateUserRequest req,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null) {
      log.info("POST /api/users — rejected: no valid admin session");
      return ResponseEntity.status(403).build();
    }

    final IamAdminProperties.UserRegistration settings = this.properties.getUserRegistration();

    final String name = req.name();
    final String email = req.email();
    final String phoneNumber = req.phoneNumber();
    final String selectedUserId = settings.isAllowSelectUserId() ? trimToNull(req.userId()) : null;
    final String temporaryPassword =
        settings.isAllowTemporaryPassword() ? trimToNull(req.temporaryPassword()) : null;
    final String pin = settings.isPersonalNumberEnabled() ? trimToNull(req.personalIdentityNumber()) : null;
    final String orgAffiliation =
        settings.isOrgAffiliationEnabled() ? trimToNull(req.orgAffiliation()) : null;
    // TODO: hsaId and efosId are picked up here when those eID attributes are implemented.

    if (name == null || name.isBlank()) {
      return ResponseEntity.badRequest().body("name must not be blank");
    }
    if (pin != null && !pin.matches("^\\d{12}$")) {
      return ResponseEntity.badRequest().body("personalIdentityNumber must be exactly 12 digits");
    }
    if (orgAffiliation != null && !ORG_AFFILIATION_PATTERN.matcher(orgAffiliation).matches()) {
      return ResponseEntity.badRequest()
          .body("orgAffiliation must be on the format userID@organization-number");
    }
    if (email != null && !email.isBlank() && !email.contains("@")) {
      return ResponseEntity.badRequest().body("email is not valid");
    }
    if (settings.isEidAttributeRequired() && pin == null && orgAffiliation == null) {
      // TODO: hsaId and efosId also satisfy this requirement when they are implemented.
      log.info("POST /api/users — rejected: no eID attribute given for user '{}'", name);
      return ResponseEntity.badRequest().body("at least one eID attribute must be given");
    }

    if (selectedUserId != null && this.keycloakAdminClient.usernameExists(selectedUserId)) {
      log.info("POST /api/users — rejected: user ID '{}' is already taken", selectedUserId);
      return ResponseEntity.status(409).body(Map.of("reason", "USER_ID_TAKEN"));
    }

    if (pin != null) {
      final Optional<String> existingUserId =
          this.keycloakAdminClient.findUserIdByPersonalIdentityNumber(pin);
      if (existingUserId.isPresent()) {
        log.info("POST /api/users — rejected: user with personalIdentityNumber '{}' already exists (id={})",
            pin, existingUserId.get());
        return ResponseEntity.status(409).body(Map.of("existingUserId", existingUserId.get()));
      }
    }
    if (orgAffiliation != null) {
      final Optional<String> existingUserId =
          this.keycloakAdminClient.findUserIdByOrgAffiliation(orgAffiliation);
      if (existingUserId.isPresent()) {
        log.info("POST /api/users — rejected: user with orgAffiliation '{}' already exists (id={})",
            orgAffiliation, existingUserId.get());
        return ResponseEntity.status(409).body(Map.of("existingUserId", existingUserId.get()));
      }
    }

    final String userId = this.keycloakAdminClient.createUser(
        selectedUserId, name, email, pin, orgAffiliation, phoneNumber, temporaryPassword);
    log.info("POST /api/users — user '{}' created with id '{}'", name, userId);

    final Map<String, String> body = new LinkedHashMap<>();
    body.put("id", userId);
    body.put("name", name);
    body.put("email", email != null ? email : "");
    body.put("userId", selectedUserId != null ? selectedUserId : "");
    body.put("personalIdentityNumber", pin != null ? pin : "");
    body.put("orgAffiliation", orgAffiliation != null ? orgAffiliation : "");
    body.put("phoneNumber", phoneNumber != null ? phoneNumber : "");
    return ResponseEntity.status(201).body(body);
  }

  /**
   * Returns a single user by their Keycloak UUID.
   *
   * @param userId  the Keycloak user UUID
   * @param request the HTTP servlet request
   * @return 200 with user data, 403 if not authenticated, 404 if not found
   */
  @GetMapping(value = "/users/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> getUser(
      @PathVariable final String userId,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null) {
      log.info("GET /api/users/{} — rejected: no valid admin session", userId);
      return ResponseEntity.status(403).build();
    }

    return this.keycloakAdminClient.fetchUserById(userId)
        .map(u -> {
          final String firstName = u.firstName() != null ? u.firstName() : "";
          final String lastName = u.lastName() != null ? u.lastName() : "";
          final String fullName = (firstName + " " + lastName).trim();
          final String name = fullName.isBlank()
              ? (u.username() != null ? u.username() : userId)
              : fullName;
          log.info("GET /api/users/{} — found user '{}'", userId, name);
          return ResponseEntity.ok(Map.of(
              "id", u.userId(),
              "name", name,
              "email", u.email() != null ? u.email() : "",
              "personalIdentityNumber", u.personalIdentityNumber() != null ? u.personalIdentityNumber() : "",
              "orgAffiliation", u.orgAffiliation() != null ? u.orgAffiliation() : "",
              "phoneNumber", u.phoneNumber() != null ? u.phoneNumber() : ""));
        })
        .orElseGet(() -> {
          log.info("GET /api/users/{} — not found", userId);
          return ResponseEntity.notFound().build();
        });
  }

  /**
   * Updates an existing user's profile fields (name, email, phone number) in Keycloak.
   *
   * <p>Personal identity number and rights are not updated here.</p>
   *
   * @param userId  the Keycloak user UUID
   * @param req     the request body
   * @param request the HTTP servlet request
   * @return 200 with updated user data on success, 400 on invalid input,
   *         403 if not authenticated or self-update, 404 if not found, 500 on Keycloak error
   */
  @PutMapping(value = "/users/{userId}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> updateUser(
      @PathVariable final String userId,
      @RequestBody final UpdateUserRequest req,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null) {
      log.info("PUT /api/users/{} — rejected: no valid admin session", userId);
      return ResponseEntity.status(403).build();
    }

    final String currentUserId = getCurrentUserId();
    if (currentUserId == null) {
      return ResponseEntity.status(403).build();
    }

    if (currentUserId.equals(userId)) {
      log.info("PUT /api/users/{} — rejected: self-update forbidden", userId);
      return ResponseEntity.status(403).build();
    }

    final String name = req.name();
    if (name == null || name.isBlank()) {
      return ResponseEntity.badRequest().body("name must not be blank");
    }
    if (req.email() != null && !req.email().isBlank() && !req.email().contains("@")) {
      return ResponseEntity.badRequest().body("email is not valid");
    }

    try {
      this.keycloakAdminClient.updateUser(userId, name, req.email(), req.phoneNumber());
      log.info("PUT /api/users/{} — profile updated", userId);

      // Re-fetch to return current state
      return this.keycloakAdminClient.fetchUserById(userId)
          .map(u -> {
            final String firstName = u.firstName() != null ? u.firstName() : "";
            final String lastName = u.lastName() != null ? u.lastName() : "";
            final String fullName = (firstName + " " + lastName).trim();
            final String resolvedName = fullName.isBlank()
                ? (u.username() != null ? u.username() : userId)
                : fullName;
            return ResponseEntity.ok(Map.of(
                "id", u.userId(),
                "name", resolvedName,
                "email", u.email() != null ? u.email() : "",
                "personalIdentityNumber", u.personalIdentityNumber() != null ? u.personalIdentityNumber() : "",
                "orgAffiliation", u.orgAffiliation() != null ? u.orgAffiliation() : "",
                "phoneNumber", u.phoneNumber() != null ? u.phoneNumber() : ""));
          })
          .orElse(ResponseEntity.notFound().build());
    }
    catch (final KeycloakAdminException e) {
      log.error("PUT /api/users/{} — Keycloak error: {}", userId, e.getMessage());
      return ResponseEntity.status(500).body(e.getMessage());
    }
  }

  /**
   * Permanently deletes a user from Keycloak.
   *
   * <p>Only superusers are permitted to call this endpoint. The caller may not delete
   * themselves. A 409 is returned if deleting the user would leave any organization or
   * function without an administrator.</p>
   *
   * @param userId  the Keycloak user UUID
   * @param request the HTTP servlet request
   * @return 204 on success, 403 if not superuser or self-delete,
   *         409 if last admin for an org or function, 500 on Keycloak error
   */
  @DeleteMapping(value = "/users/{userId}")
  public ResponseEntity<?> deleteUser(
      @PathVariable final String userId,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null) {
      log.info("DELETE /api/users/{} — rejected: no valid admin session", userId);
      return ResponseEntity.status(403).build();
    }

    if (!data.currentUserIsSuperuser()) {
      log.info("DELETE /api/users/{} — rejected: caller is not a superuser", userId);
      return ResponseEntity.status(403).build();
    }

    final String currentUserId = getCurrentUserId();
    if (currentUserId == null) {
      return ResponseEntity.status(403).build();
    }

    if (currentUserId.equals(userId)) {
      log.info("DELETE /api/users/{} — rejected: self-deletion forbidden", userId);
      return ResponseEntity.status(403).build();
    }

    // Safety check: ensure no org or function is left without an admin (live Keycloak query)
    for (final UserRight right : this.keycloakAdminClient.fetchUserRights(userId)) {
      if (!"admin".equals(right.right())) {
        continue;
      }
      final boolean otherAdminExists;
      if (right.functionId() == null) {
        otherAdminExists = this.keycloakAdminClient.hasOtherOrgAdmin(right.orgIdentifier(), userId);
      }
      else {
        otherAdminExists = this.keycloakAdminClient.hasOtherFunctionAdmin(
            right.orgIdentifier(), right.functionId(), userId);
      }
      if (!otherAdminExists) {
        log.info("DELETE /api/users/{} — rejected: last admin for org '{}' function '{}'",
            userId, right.orgIdentifier(), right.functionId());
        return ResponseEntity.status(409)
            .body(Map.of("reason", "LAST_ADMIN", "scope", right.orgIdentifier()));
      }
    }

    try {
      this.keycloakAdminClient.deleteUser(userId);
      log.info("DELETE /api/users/{} — user permanently deleted", userId);
      return ResponseEntity.noContent().build();
    }
    catch (final KeycloakAdminException e) {
      log.error("DELETE /api/users/{} — Keycloak error: {}", userId, e.getMessage());
      return ResponseEntity.status(500).body(e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static UserResponse toResponse(final UserInfo u, final List<UserRight> rights) {
    final List<UserRightResponse> rightResponses = rights.stream()
        .map(r -> new UserRightResponse(r.orgIdentifier(), r.functionId(), r.right()))
        .toList();
    return new UserResponse(
        u.userId(),
        u.username(),
        u.firstName(),
        u.lastName(),
        u.email(),
        u.personalIdentityNumber(),
        u.orgAffiliation(),
        u.phoneNumber(),
        u.superuser(),
        rightResponses);
  }

  /**
   * Returns the trimmed value, or {@code null} if the value is {@code null} or blank.
   *
   * @param value the value to trim
   * @return the trimmed value or {@code null}
   */
  private static @Nullable String trimToNull(final @Nullable String value) {
    if (value == null) {
      return null;
    }
    final String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String getCurrentUserId() {
    final var auth = SecurityContextHolder.getContext().getAuthentication();
    return (auth != null && auth.getPrincipal() instanceof final OidcUser oidc)
        ? oidc.getSubject()
        : null;
  }

}
