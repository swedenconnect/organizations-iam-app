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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.client.HttpClientErrorException;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.CreateUserRequest;
import se.swedenconnect.iam.admin.controllers.dto.UpdateUserRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.UserInfo;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that an email address that is already registered is reported as a conflict the
 * administrator can act on, and not as a server error, on both {@code POST /api/users} and
 * {@code PUT /api/users/{userId}}. See
 * <a href="https://github.com/swedenconnect/organizations-iam-app/issues/184">issue #184</a>.
 *
 * <p>The address is refused before the write when it is known to be taken, and a conflict Keycloak
 * reports anyway — a race, or a rule only Keycloak knows — is answered the same way. Which user
 * holds the address is never disclosed, and a Keycloak failure of any other kind stays a failure.
 * </p>
 *
 * @author Per Fredrik Plars
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserEmailConflictTest {

  private static final String NAME = "Martin Lindström";
  private static final String EMAIL = "martin@example.com";
  private static final String PIN = "196911292032";
  private static final String CALLER = "caller-uuid";
  private static final String TARGET = "target-uuid";
  private static final String OTHER = "other-uuid";

  /** What Keycloak answers when the realm does not allow an address to be registered twice. */
  private static final String KEYCLOAK_EMAIL_CONFLICT = "{\"errorMessage\":\"User exists with same email\"}";

  @Mock
  private KeycloakAdminClient keycloakAdminClient;

  @Mock
  private HttpServletRequest request;

  @Mock
  private HttpSession session;

  private UserController controller;

  @BeforeEach
  void setUp() {
    this.controller = new UserController(
        this.keycloakAdminClient, new IamAdminProperties(), mock(ApplicationEventPublisher.class));

    when(this.request.getSession(false)).thenReturn(this.session);
    final AdminSessionData data = new AdminSessionData(true, null, null, List.of(), Set.of(),
        new OrgRightsClaim(true, List.of()));
    when(this.session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)).thenReturn(data);
    setupCallerAuth();

    when(this.keycloakAdminClient.findUserIdByPersonalIdentityNumber(anyString())).thenReturn(Optional.empty());
    when(this.keycloakAdminClient.findUserIdByOrgAffiliation(anyString())).thenReturn(Optional.empty());
    when(this.keycloakAdminClient.findUserIdByEmail(anyString())).thenReturn(Optional.empty());
    when(this.keycloakAdminClient.usernameExists(anyString())).thenReturn(false);
    when(this.keycloakAdminClient.createUser(any(), anyString(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn("created-uuid");
    when(this.keycloakAdminClient.fetchUserById(TARGET)).thenReturn(Optional.of(userInfo()));
    when(this.keycloakAdminClient.fetchUserRights(anyString())).thenReturn(List.of());
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  // ---------------------------------------------------------------------------
  // POST /api/users
  // ---------------------------------------------------------------------------

  /** An address that is already registered is refused before anything is written. */
  @Test
  void create_emailAlreadyRegistered_returns409AndWritesNothing() {
    when(this.keycloakAdminClient.findUserIdByEmail(EMAIL)).thenReturn(Optional.of(OTHER));

    final ResponseEntity<?> response = this.controller.createUser(
        new CreateUserRequest(NAME, EMAIL, null, PIN, null, null, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody()).isEqualTo(Map.of("reason", "EMAIL_TAKEN"));
    verify(this.keycloakAdminClient, never())
        .createUser(any(), anyString(), any(), any(), any(), any(), any(), any(), any());
  }

  /** The user holding the address is not disclosed, unlike for the other identities. */
  @Test
  void create_emailAlreadyRegistered_doesNotDiscloseTheExistingUser() {
    when(this.keycloakAdminClient.findUserIdByEmail(EMAIL)).thenReturn(Optional.of(OTHER));

    final ResponseEntity<?> response = this.controller.createUser(
        new CreateUserRequest(NAME, EMAIL, null, PIN, null, null, null, null, null), this.request);

    assertThat(response.getBody()).asString().doesNotContain(OTHER);
  }

  /** A conflict Keycloak reports anyway, after the check passed, is answered the same way. */
  @Test
  void create_keycloakReportsEmailConflict_returns409() {
    when(this.keycloakAdminClient.createUser(any(), anyString(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(emailConflict("POST /users"));

    final ResponseEntity<?> response = this.controller.createUser(
        new CreateUserRequest(NAME, EMAIL, null, PIN, null, null, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody()).isEqualTo(Map.of("reason", "EMAIL_TAKEN"));
  }

  /** A Keycloak failure of any other kind is still a failure. */
  @Test
  void create_keycloakFailsForAnotherReason_isNotTurnedIntoAConflict() {
    when(this.keycloakAdminClient.createUser(any(), anyString(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(serverError("POST /users"));

    assertThatThrownBy(() -> this.controller.createUser(
        new CreateUserRequest(NAME, EMAIL, null, PIN, null, null, null, null, null), this.request))
        .isInstanceOf(KeycloakAdminException.class);
  }

  /** A conflict over something else than the email address is not reported as an email conflict. */
  @Test
  void create_keycloakReportsAnotherConflict_isNotTurnedIntoAnEmailConflict() {
    when(this.keycloakAdminClient.createUser(any(), anyString(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(conflict("POST /users", "{\"errorMessage\":\"User exists with same username\"}"));

    assertThatThrownBy(() -> this.controller.createUser(
        new CreateUserRequest(NAME, EMAIL, null, PIN, null, null, null, null, null), this.request))
        .isInstanceOf(KeycloakAdminException.class);
  }

  // ---------------------------------------------------------------------------
  // PUT /api/users/{userId}
  // ---------------------------------------------------------------------------

  /** An address another user holds is refused before anything is written. */
  @Test
  void update_emailAlreadyRegisteredForAnotherUser_returns409AndWritesNothing() {
    when(this.keycloakAdminClient.findUserIdByEmail("taken@example.com")).thenReturn(Optional.of(OTHER));

    final ResponseEntity<?> response = this.controller.updateUser(
        TARGET, new UpdateUserRequest("New Name", "taken@example.com", null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody()).isEqualTo(Map.of("reason", "EMAIL_TAKEN"));
    verify(this.keycloakAdminClient, never()).updateUser(anyString(), anyString(), any(), any());
  }

  /** The user's own address is not a conflict with themselves. */
  @Test
  void update_emailHeldByTheUserThemselves_isAllowed() {
    when(this.keycloakAdminClient.findUserIdByEmail(EMAIL)).thenReturn(Optional.of(TARGET));

    final ResponseEntity<?> response = this.controller.updateUser(
        TARGET, new UpdateUserRequest("New Name", EMAIL, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    verify(this.keycloakAdminClient).updateUser(TARGET, "New Name", EMAIL, null);
  }

  /** A conflict Keycloak reports anyway, after the check passed, is answered the same way. */
  @Test
  void update_keycloakReportsEmailConflict_returns409() {
    doThrow(emailConflict("PUT /users/" + TARGET))
        .when(this.keycloakAdminClient).updateUser(anyString(), anyString(), any(), any());

    final ResponseEntity<?> response = this.controller.updateUser(
        TARGET, new UpdateUserRequest("New Name", EMAIL, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody()).isEqualTo(Map.of("reason", "EMAIL_TAKEN"));
  }

  /** Another Keycloak failure is a 500, and the Keycloak message is not passed on. */
  @Test
  void update_keycloakFailsForAnotherReason_returns500WithoutTheKeycloakMessage() {
    doThrow(serverError("PUT /users/" + TARGET))
        .when(this.keycloakAdminClient).updateUser(anyString(), anyString(), any(), any());

    final ResponseEntity<?> response = this.controller.updateUser(
        TARGET, new UpdateUserRequest("New Name", EMAIL, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getBody()).isNull();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static KeycloakAdminException emailConflict(final String operation) {
    return conflict(operation, KEYCLOAK_EMAIL_CONFLICT);
  }

  private static KeycloakAdminException conflict(final String operation, final String body) {
    return KeycloakAdminException.forResponse(operation, new HttpClientErrorException(
        HttpStatus.CONFLICT, "Conflict", body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
  }

  private static KeycloakAdminException serverError(final String operation) {
    return KeycloakAdminException.forResponse(operation, new HttpClientErrorException(
        HttpStatus.INTERNAL_SERVER_ERROR, "Server Error", new byte[0], StandardCharsets.UTF_8));
  }

  private static UserInfo userInfo() {
    return new UserInfo(
        TARGET, TARGET, "Klara", "Karlsson", EMAIL, null, null, null, null, null, false, List.of());
  }

  private static void setupCallerAuth() {
    final OidcIdToken idToken = new OidcIdToken("token", Instant.now(),
        Instant.now().plusSeconds(3600), Map.of("sub", CALLER));
    final OidcUser principal = new DefaultOidcUser(
        List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken);
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

}
