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
import se.swedenconnect.iam.admin.audit.AuditData;
import se.swedenconnect.iam.admin.audit.AuditOperations;
import se.swedenconnect.iam.admin.audit.IamAuditEvent;
import se.swedenconnect.iam.admin.audit.IamErrorAuditEvent;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.CreateUserRequest;
import se.swedenconnect.iam.admin.controllers.dto.UpdateUserRequest;
import se.swedenconnect.iam.admin.controllers.dto.UserPageResponse;
import se.swedenconnect.iam.admin.controllers.dto.UserResponse;
import se.swedenconnect.iam.admin.controllers.dto.UserRightResponse;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.PersonNameRule;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.RightHolder;
import se.swedenconnect.iam.admin.keycloak.model.UserInfo;
import se.swedenconnect.iam.admin.keycloak.model.UserRight;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * <p>A user without a single right is outside every org-admin's scope, see
 * {@link #listUsers}, so such an admin creates users from the organization or function the user is
 * to hold a right in, which grants the right in the same interaction. The admin application offers
 * creating a user on its own only to a superuser.</p>
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

  private final ApplicationEventPublisher eventPublisher;

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
   * <p>For a regular admin each user carries only the rights the caller is entitled to see, see
   * {@link AdminSessionData#mayViewRight(UserRight)}, and a user left without a single visible
   * right is left out of the response altogether. The reported total counts the users that remain
   * after that filtering.</p>
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
      log.info("GET /api/users — denied: no valid admin session");
      return this.denied(AuditOperations.LIST_USERS, AuditOperations.NO_ADMIN_SESSION, AuditData.Target.none());
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
      final List<String> candidateUserIds =
          this.keycloakAdminClient.fetchUserIdsForOrgs(data.adminOrgIdentifiers());

      // A user is only shown with the rights the caller is entitled to see, and a user left
      // without any visible right is not shown at all. That decision is taken before pagination,
      // so the reported total counts only the users that survive it.
      final List<VisibleUser> visibleUsers = new ArrayList<>();
      for (final String userId : candidateUserIds) {
        final List<UserRight> rights = this.keycloakAdminClient.fetchUserRights(userId).stream()
            .filter(data::mayViewRight)
            .toList();
        if (!rights.isEmpty()) {
          visibleUsers.add(new VisibleUser(userId, rights));
        }
      }

      total = visibleUsers.size();
      final int fromIndex = Math.min(first, total);
      final int toIndex = Math.min(first + effectiveSize, total);
      content = visibleUsers.subList(fromIndex, toIndex).stream()
          .map(v -> {
            final UserInfo u = this.keycloakAdminClient.fetchUserById(v.userId()).orElse(null);
            return u != null ? toResponse(u, v.rights()) : null;
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
   *         409 if the user ID is taken, if the email address is already registered, or if a user
   *         with the same personal identity number, organizational affiliation, HSA-ID or EFOS-ID
   *         already exists
   */
  @PostMapping(value = "/users",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> createUser(
      @RequestBody final CreateUserRequest req,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null) {
      log.info("POST /api/users — denied: no valid admin session");
      return this.denied(AuditOperations.CREATE_USER, AuditOperations.NO_ADMIN_SESSION, AuditData.Target.none());
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
    final String hsaId = settings.isHsaIdEnabled() ? trimToNull(req.hsaId()) : null;
    final String efosId = settings.isEfosIdEnabled() ? trimToNull(req.efosId()) : null;

    if (name == null || name.isBlank()) {
      return ResponseEntity.badRequest().body("name must not be blank");
    }
    if (!PersonNameRule.isAllowed(name)) {
      log.info("POST /api/users — rejected: the name carries a character the realm refuses");
      return ResponseEntity.badRequest().body(PersonNameRule.rejectionMessage());
    }
    if (pin != null && !pin.matches("^\\d{12}$")) {
      return ResponseEntity.badRequest().body("personalIdentityNumber must be exactly 12 digits");
    }
    if (orgAffiliation != null && !ORG_AFFILIATION_PATTERN.matcher(orgAffiliation).matches()) {
      return ResponseEntity.badRequest()
          .body("orgAffiliation must be on the format userID@organization-number");
    }
    // The HSA-ID and EFOS-ID are placed within quotes in the Keycloak user lookup, so a quote of
    // their own would change the query.
    if (hsaId != null && hsaId.indexOf('"') >= 0) {
      return ResponseEntity.badRequest().body("hsaId must not contain a quotation mark (\")");
    }
    if (efosId != null && efosId.indexOf('"') >= 0) {
      return ResponseEntity.badRequest().body("efosId must not contain a quotation mark (\")");
    }
    if (email != null && !email.isBlank() && !email.contains("@")) {
      return ResponseEntity.badRequest().body("email is not valid");
    }
    if (settings.isEidAttributeRequired() && pin == null && orgAffiliation == null && hsaId == null
        && efosId == null) {
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
    // The HSA-ID and EFOS-ID belong in the audit log only, so the values are not logged here.
    if (hsaId != null) {
      final Optional<String> existingUserId = this.keycloakAdminClient.findUserIdByHsaId(hsaId);
      if (existingUserId.isPresent()) {
        log.info("POST /api/users — rejected: user with the given HSA-ID already exists (id={})",
            existingUserId.get());
        return ResponseEntity.status(409).body(Map.of("existingUserId", existingUserId.get()));
      }
    }
    if (efosId != null) {
      final Optional<String> existingUserId = this.keycloakAdminClient.findUserIdByEfosId(efosId);
      if (existingUserId.isPresent()) {
        log.info("POST /api/users — rejected: user with the given EFOS-ID already exists (id={})",
            existingUserId.get());
        return ResponseEntity.status(409).body(Map.of("existingUserId", existingUserId.get()));
      }
    }

    if (email != null && !email.isBlank()
        && this.keycloakAdminClient.findUserIdByEmail(email).isPresent()) {
      log.info("POST /api/users — rejected: email '{}' is already registered", email);
      return ResponseEntity.status(409).body(Map.of("reason", "EMAIL_TAKEN"));
    }

    final String userId;
    try {
      userId = this.keycloakAdminClient.createUser(
          selectedUserId, name, email, pin, orgAffiliation, hsaId, efosId, phoneNumber, temporaryPassword);
    }
    catch (final KeycloakAdminException e) {
      if (isEmailConflict(e)) {
        log.info("POST /api/users — rejected by Keycloak: email '{}' is already registered", email);
        return ResponseEntity.status(409).body(Map.of("reason", "EMAIL_TAKEN"));
      }
      this.eventPublisher.publishEvent(IamErrorAuditEvent.operationFailed(
          new AuditData.Operation(AuditOperations.CREATE_USER, null), AuditData.Target.none(), e));
      throw e;
    }
    log.info("POST /api/users — user '{}' created with id '{}'", name, userId);
    this.eventPublisher.publishEvent(IamAuditEvent.userCreated(new AuditData.User(
        userId, name, pin, orgAffiliation, hsaId, efosId, selectedUserId, temporaryPassword != null), null));

    final Map<String, String> body = new LinkedHashMap<>();
    body.put("id", userId);
    body.put("name", name);
    body.put("email", email != null ? email : "");
    body.put("userId", selectedUserId != null ? selectedUserId : "");
    body.put("personalIdentityNumber", pin != null ? pin : "");
    body.put("orgAffiliation", orgAffiliation != null ? orgAffiliation : "");
    body.put("hsaId", hsaId != null ? hsaId : "");
    body.put("efosId", efosId != null ? efosId : "");
    body.put("phoneNumber", phoneNumber != null ? phoneNumber : "");
    return ResponseEntity.status(201).body(body);
  }

  /**
   * Returns a single user by their Keycloak UUID.
   *
   * <p>A regular admin may only address a user that is visible to them, see
   * {@code resolveVisibleUser}. A user outside the caller's scope is
   * reported exactly as an unknown UUID is.</p>
   *
   * @param userId  the Keycloak user UUID
   * @param request the HTTP servlet request
   * @return 200 with user data, 403 if not authenticated, 404 if not found or outside the
   *         caller's scope
   */
  @GetMapping(value = "/users/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> getUser(
      @PathVariable final String userId,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null) {
      log.info("GET /api/users/{} — denied: no valid admin session", userId);
      return this.denied(AuditOperations.READ_USER, AuditOperations.NO_ADMIN_SESSION,
          AuditData.Target.none().user(this.auditUser(userId)));
    }

    return this.resolveVisibleUser(data, userId, AuditOperations.READ_USER)
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
              "hsaId", u.hsaId() != null ? u.hsaId() : "",
              "efosId", u.efosId() != null ? u.efosId() : "",
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
   * <p>The eID attributes and rights are not updated here.</p>
   *
   * <p>An email address another user already holds is refused with a 409 and
   * {@code {"reason":"EMAIL_TAKEN"}}, the same answer the create endpoint gives. Which user holds
   * the address is not disclosed.</p>
   *
   * <p>A regular admin may only address a user that is visible to them, see
   * {@code resolveVisibleUser}. A user outside the caller's scope is
   * reported exactly as an unknown UUID is, and is left unchanged.</p>
   *
   * @param userId  the Keycloak user UUID
   * @param req     the request body
   * @param request the HTTP servlet request
   * @return 200 with updated user data on success, 400 on invalid input,
   *         403 if not authenticated or self-update, 404 if not found or outside the caller's
   *         scope, 409 if the email address is already registered for another user,
   *         500 on Keycloak error
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
      log.info("PUT /api/users/{} — denied: no valid admin session", userId);
      return this.denied(AuditOperations.UPDATE_USER, AuditOperations.NO_ADMIN_SESSION,
          AuditData.Target.none().user(this.auditUser(userId)));
    }

    final String currentUserId = getCurrentUserId();
    if (currentUserId == null) {
      return this.denied(AuditOperations.UPDATE_USER, AuditOperations.NO_ADMIN_SESSION,
          AuditData.Target.none().user(this.auditUser(userId)));
    }

    if (currentUserId.equals(userId)) {
      log.info("PUT /api/users/{} — denied: self-update forbidden", userId);
      return this.denied(AuditOperations.UPDATE_USER, AuditOperations.SELF_MODIFICATION,
          AuditData.Target.none().user(this.auditUser(userId)));
    }

    final String name = req.name();
    if (name == null || name.isBlank()) {
      return ResponseEntity.badRequest().body("name must not be blank");
    }
    if (!PersonNameRule.isAllowed(name)) {
      log.info("PUT /api/users/{} — rejected: the name carries a character the realm refuses", userId);
      return ResponseEntity.badRequest().body(PersonNameRule.rejectionMessage());
    }
    if (req.email() != null && !req.email().isBlank() && !req.email().contains("@")) {
      return ResponseEntity.badRequest().body("email is not valid");
    }

    final UserInfo existing = this.resolveVisibleUser(data, userId, AuditOperations.UPDATE_USER).orElse(null);
    if (existing == null) {
      log.info("PUT /api/users/{} — not found", userId);
      return ResponseEntity.notFound().build();
    }

    if (req.email() != null && !req.email().isBlank()
        && this.keycloakAdminClient.findUserIdByEmail(req.email())
            .filter(holder -> !holder.equals(userId)).isPresent()) {
      log.info("PUT /api/users/{} — rejected: email '{}' is already registered", userId, req.email());
      return ResponseEntity.status(409).body(Map.of("reason", "EMAIL_TAKEN"));
    }

    try {
      this.keycloakAdminClient.updateUser(userId, name, req.email(), req.phoneNumber());
      log.info("PUT /api/users/{} — profile updated", userId);

      final AuditData.Change change = AuditData.Change.between(
          profileFields(AuditData.User.displayName(existing), existing.email(), existing.phoneNumber()),
          profileFields(name, req.email(), req.phoneNumber()),
          false);
      if (change != null) {
        this.eventPublisher.publishEvent(IamAuditEvent.userUpdated(AuditData.User.of(userId, name), change));
      }

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
                "hsaId", u.hsaId() != null ? u.hsaId() : "",
                "efosId", u.efosId() != null ? u.efosId() : "",
                "phoneNumber", u.phoneNumber() != null ? u.phoneNumber() : ""));
          })
          .orElse(ResponseEntity.notFound().build());
    }
    catch (final KeycloakAdminException e) {
      if (isEmailConflict(e)) {
        log.info("PUT /api/users/{} — rejected by Keycloak: email '{}' is already registered",
            userId, req.email());
        return ResponseEntity.status(409).body(Map.of("reason", "EMAIL_TAKEN"));
      }
      log.error("PUT /api/users/{} — Keycloak error: {}", userId, e.getMessage());
      this.eventPublisher.publishEvent(IamErrorAuditEvent.operationFailed(
          new AuditData.Operation(AuditOperations.UPDATE_USER, null),
          AuditData.Target.none().user(AuditData.User.of(existing)), e));
      return ResponseEntity.status(500).build();
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
      log.info("DELETE /api/users/{} — denied: no valid admin session", userId);
      return this.denied(AuditOperations.DELETE_USER, AuditOperations.NO_ADMIN_SESSION,
          AuditData.Target.none().user(this.auditUser(userId)));
    }

    if (!data.currentUserIsSuperuser()) {
      log.info("DELETE /api/users/{} — denied: caller is not a superuser", userId);
      return this.denied(AuditOperations.DELETE_USER, AuditOperations.NOT_SUPERUSER,
          AuditData.Target.none().user(this.auditUser(userId)));
    }

    final String currentUserId = getCurrentUserId();
    if (currentUserId == null) {
      return this.denied(AuditOperations.DELETE_USER, AuditOperations.NO_ADMIN_SESSION,
          AuditData.Target.none().user(this.auditUser(userId)));
    }

    if (currentUserId.equals(userId)) {
      log.info("DELETE /api/users/{} — denied: self-deletion forbidden", userId);
      return this.denied(AuditOperations.DELETE_USER, AuditOperations.SELF_MODIFICATION,
          AuditData.Target.none().user(this.auditUser(userId)));
    }

    // The user and the rights are read before the deletion, since the audit entry records them.
    final UserInfo user = this.keycloakAdminClient.fetchUserById(userId).orElse(null);
    final AuditData.User auditUser = user != null ? AuditData.User.withIdentity(user) : AuditData.User.of(userId, null);
    final List<UserRight> rights = this.keycloakAdminClient.fetchUserRights(userId);

    // Safety check: ensure no org or function is left without an admin (live Keycloak query)
    for (final UserRight right : rights) {
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
        this.eventPublisher.publishEvent(IamAuditEvent.operationRejected(
            new AuditData.Operation(AuditOperations.DELETE_USER, AuditOperations.LAST_ADMIN),
            AuditData.Target.none()
                .user(AuditData.User.of(userId, auditUser.displayName()))
                .organization(right.orgIdentifier())
                .function(right.functionId())
                .right(right.right())));
        return ResponseEntity.status(409)
            .body(Map.of("reason", "LAST_ADMIN", "scope", right.orgIdentifier()));
      }
    }

    try {
      this.keycloakAdminClient.deleteUser(userId);
      log.info("DELETE /api/users/{} — user permanently deleted", userId);
    }
    catch (final KeycloakAdminException e) {
      log.error("DELETE /api/users/{} — Keycloak error: {}", userId, e.getMessage());
      this.eventPublisher.publishEvent(IamErrorAuditEvent.operationFailed(
          new AuditData.Operation(AuditOperations.DELETE_USER, null),
          AuditData.Target.none().user(AuditData.User.of(userId, auditUser.displayName())), e));
      return ResponseEntity.status(500).build();
    }
    this.eventPublisher.publishEvent(IamAuditEvent.userDeleted(auditUser, rights.stream()
        .map(r -> new RightHolder(userId, auditUser.displayName(), r.orgIdentifier(), r.functionId(), r.right()))
        .toList()));
    return ResponseEntity.noContent().build();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Tells whether Keycloak refused the write because the email address is already registered.
   *
   * <p>Keycloak answers {@code 409} with {@code User exists with same email} when the realm does
   * not allow an address to be used twice. That is the administrator's to fix, so it is answered
   * with a 409 of our own rather than a server error. The match is on the status and the message
   * Keycloak sends; a conflict it reports for any other reason is left alone.</p>
   *
   * @param e the exception from the Keycloak admin client
   * @return {@code true} for an email conflict
   */
  private static boolean isEmailConflict(final @NonNull KeycloakAdminException e) {
    final String message = e.getKeycloakErrorMessage();
    return e.isConflict() && message != null
        && message.toLowerCase(Locale.ROOT).contains("same email");
  }

  /**
   * Loads a user the caller is entitled to address, by the same visibility rule the list endpoint
   * applies, see {@link AdminSessionData#mayViewUser(List)}.
   *
   * <p>An empty result means the caller must be answered with a 404: either there is no such user,
   * or the user holds no right the caller may see. The two must not be told apart, so that a
   * response never confirms that a UUID belongs to an existing user.</p>
   *
   * <p>The rights are read from Keycloak on every request rather than taken from the session, so a
   * right granted moments earlier makes the user addressable in the same interaction. The
   * organizations view depends on that: adding a user to an organization grants the right and then
   * fetches the user by UUID.</p>
   *
   * <p>A user hidden for being outside the caller's scope is recorded as an {@code access_denied}
   * audit event, while the response stays the same as for an unknown UUID.</p>
   *
   * @param data      the caller's session data
   * @param userId    the Keycloak user UUID
   * @param operation the operation, for the audit event
   * @return the user, or empty if there is no such user or the caller may not address them
   */
  private Optional<UserInfo> resolveVisibleUser(
      final @NonNull AdminSessionData data, final @NonNull String userId, final @NonNull String operation) {

    final Optional<UserInfo> user = this.keycloakAdminClient.fetchUserById(userId);
    if (user.isEmpty() || data.currentUserIsSuperuser()) {
      return user;
    }
    if (!data.mayViewUser(this.keycloakAdminClient.fetchUserRights(userId))) {
      log.info("User '{}' is outside the caller's scope and is reported as not found", userId);
      this.eventPublisher.publishEvent(IamAuditEvent.accessDenied(
          new AuditData.Operation(operation, AuditOperations.OUT_OF_SCOPE),
          AuditData.Target.none().user(AuditData.User.of(user.get()))));
      return Optional.empty();
    }
    return user;
  }

  /**
   * Records an {@code access_denied} audit event and returns the 403 response.
   *
   * @param operation the operation refused
   * @param reason    the reason
   * @param target    what the operation was aimed at
   * @param <T>       the response body type
   * @return a 403 response
   */
  private <T> ResponseEntity<T> denied(
      final @NonNull String operation, final @NonNull String reason, final AuditData.@NonNull Target target) {
    this.eventPublisher.publishEvent(
        IamAuditEvent.accessDenied(new AuditData.Operation(operation, reason), target));
    return ResponseEntity.status(403).build();
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

  /**
   * The profile fields an update may change, by audit field name.
   *
   * @param name        the name
   * @param email       the email address
   * @param phoneNumber the phone number
   * @return the fields
   */
  private static Map<String, @Nullable String> profileFields(
      final @Nullable String name, final @Nullable String email, final @Nullable String phoneNumber) {
    final Map<String, @Nullable String> fields = new LinkedHashMap<>();
    fields.put("name", name);
    fields.put("email", email);
    fields.put("phone_number", phoneNumber);
    return fields;
  }

  /**
   * A candidate user for the list along with the rights the caller is entitled to see. The profile
   * itself is loaded only for the users on the requested page.
   *
   * @param userId the Keycloak user UUID
   * @param rights the rights visible to the caller; never empty
   */
  private record VisibleUser(@NonNull String userId, @NonNull List<UserRight> rights) {
  }

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
        u.hsaId(),
        u.efosId(),
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
