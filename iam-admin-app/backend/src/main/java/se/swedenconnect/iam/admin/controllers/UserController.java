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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.swedenconnect.iam.admin.controllers.dto.CreateUserRequest;
import se.swedenconnect.iam.admin.controllers.dto.UpdateUserRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.UserInfo;
import se.swedenconnect.iam.admin.keycloak.model.UserRight;

import java.util.Map;

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

  /**
   * Creates a new user in Keycloak.
   *
   * @param req     the request body
   * @param request the HTTP servlet request
   * @return 201 Created with the created user, 400 on invalid input, 403 if not authenticated,
   *         409 if a user with the same personal identity number already exists
   */
  @PostMapping(value = "/users",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> createUser(
      @RequestBody final CreateUserRequest req,
      final HttpServletRequest request) {

    final HttpSession session = request.getSession(false);
    final Object attr = session != null
        ? session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)
        : null;

    if (!(attr instanceof AdminSessionData)) {
      log.info("POST /api/users — rejected: no valid admin session");
      return ResponseEntity.status(403).build();
    }

    final String name = req.name();
    final String email = req.email();
    final String pin = req.personalIdentityNumber();
    final String phoneNumber = req.phoneNumber();

    if (name == null || name.isBlank()) {
      return ResponseEntity.badRequest().body("name must not be blank");
    }
    if (pin == null || !pin.matches("^\\d{12}$")) {
      return ResponseEntity.badRequest().body("personalIdentityNumber must be exactly 12 digits");
    }
    if (email != null && !email.isBlank() && !email.contains("@")) {
      return ResponseEntity.badRequest().body("email is not valid");
    }

    final java.util.Optional<String> existingUserId =
        this.keycloakAdminClient.findUserIdByPersonalIdentityNumber(pin);
    if (existingUserId.isPresent()) {
      log.info("POST /api/users — rejected: user with personalIdentityNumber '{}' already exists (id={})",
          pin, existingUserId.get());
      return ResponseEntity.status(409).body(Map.of("existingUserId", existingUserId.get()));
    }

    final String userId = this.keycloakAdminClient.createUser(name, email, pin, phoneNumber);
    log.info("POST /api/users — user '{}' created with id '{}'", name, userId);

    return ResponseEntity.status(201).body(Map.of(
        "id", userId,
        "name", name,
        "email", email != null ? email : "",
        "personalIdentityNumber", pin,
        "phoneNumber", phoneNumber != null ? phoneNumber : ""));
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

    final HttpSession session = request.getSession(false);
    final Object attr = session != null
        ? session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)
        : null;

    if (!(attr instanceof AdminSessionData)) {
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

    final HttpSession session = request.getSession(false);
    final Object attr = session != null
        ? session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)
        : null;

    if (!(attr instanceof AdminSessionData)) {
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

    final HttpSession session = request.getSession(false);
    final Object attr = session != null
        ? session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)
        : null;

    if (!(attr instanceof final AdminSessionData data)) {
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

    // Safety check: ensure no org or function is left without an admin
    final UserInfo target = data.users().stream()
        .filter(u -> u.userId().equals(userId))
        .findFirst()
        .orElse(null);

    if (target != null) {
      for (final UserRight right : target.rights()) {
        if (!"admin".equals(right.right())) continue;

        final boolean otherAdminExists;
        if (right.functionId() == null) {
          // Org-level admin — need another org-level admin for the same org
          otherAdminExists = data.users().stream()
              .filter(u -> !u.userId().equals(userId))
              .flatMap(u -> u.rights().stream())
              .anyMatch(r -> r.orgIdentifier().equals(right.orgIdentifier())
                  && r.functionId() == null
                  && "admin".equals(r.right()));
        } else {
          // Function-level admin — need another function-level admin OR an org-level admin
          otherAdminExists = data.users().stream()
              .filter(u -> !u.userId().equals(userId))
              .flatMap(u -> u.rights().stream())
              .anyMatch(r -> r.orgIdentifier().equals(right.orgIdentifier())
                  && "admin".equals(r.right())
                  && (right.functionId().equals(r.functionId()) || r.functionId() == null));
        }

        if (!otherAdminExists) {
          log.info("DELETE /api/users/{} — rejected: last admin for org '{}' function '{}'",
              userId, right.orgIdentifier(), right.functionId());
          return ResponseEntity.status(409)
              .body(Map.of("reason", "LAST_ADMIN", "scope", right.orgIdentifier()));
        }
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

  private static String getCurrentUserId() {
    final var auth = SecurityContextHolder.getContext().getAuthentication();
    return (auth != null && auth.getPrincipal() instanceof final OidcUser oidc)
        ? oidc.getSubject()
        : null;
  }

}
