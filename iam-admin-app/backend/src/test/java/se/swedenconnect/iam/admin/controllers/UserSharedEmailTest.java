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
import static org.assertj.core.api.InstanceOfAssertFactories.throwable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that two users may hold the same email address, on both {@code POST /api/users} and
 * {@code PUT /api/users/{userId}}. See
 * <a href="https://github.com/swedenconnect/organizations-iam-app/issues/191">issue #191</a>.
 *
 * <p>The address is contact information, not an identity: the application neither looks a user up
 * by it nor refuses it because somebody else has it. A realm that does forbid a shared address
 * answers 409, which is passed on as the conflict it is rather than hidden, and a Keycloak failure
 * of any other kind is a 500 that carries nothing Keycloak said.</p>
 *
 * @author Per Fredrik Plars
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserSharedEmailTest {

  private static final String NAME = "Martin Lindström";
  private static final String EMAIL = "martin@example.com";
  private static final String PIN = "196911292032";
  private static final String CALLER = "caller-uuid";
  private static final String TARGET = "target-uuid";

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

  /** An address somebody else already holds is created like any other. */
  @Test
  void create_emailAnotherUserHolds_isAllowed() {
    final ResponseEntity<?> response = this.controller.createUser(
        new CreateUserRequest(NAME, EMAIL, null, PIN, null, null, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.keycloakAdminClient)
        .createUser(any(), anyString(), eq(EMAIL), any(), any(), any(), any(), any(), any());
  }

  /** A realm that does forbid a shared address answers 409, and that is not swallowed. */
  @Test
  void create_realmForbidsASharedAddress_theConflictIsPassedOn() {
    when(this.keycloakAdminClient.createUser(any(), anyString(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(emailConflict("POST /users"));

    assertThatThrownBy(() -> this.controller.createUser(
        new CreateUserRequest(NAME, EMAIL, null, PIN, null, null, null, null, null), this.request))
        .asInstanceOf(throwable(KeycloakAdminException.class))
        .satisfies(e -> assertThat(e.getStatus()).isEqualTo(409));
  }

  // ---------------------------------------------------------------------------
  // PUT /api/users/{userId}
  // ---------------------------------------------------------------------------

  /** An address another user holds is saved like any other. */
  @Test
  void update_emailAnotherUserHolds_isAllowed() {
    final ResponseEntity<?> response = this.controller.updateUser(
        TARGET, new UpdateUserRequest("New Name", "shared@example.com", null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    verify(this.keycloakAdminClient).updateUser(TARGET, "New Name", "shared@example.com", null);
  }

  /** The user's own address is saved unchanged. */
  @Test
  void update_theUsersOwnAddress_isAllowed() {
    final ResponseEntity<?> response = this.controller.updateUser(
        TARGET, new UpdateUserRequest("New Name", EMAIL, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    verify(this.keycloakAdminClient).updateUser(TARGET, "New Name", EMAIL, null);
  }

  /** A Keycloak failure is a 500, and the Keycloak message is not passed on. */
  @Test
  void update_keycloakFails_returns500WithoutTheKeycloakMessage() {
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
    return KeycloakAdminException.forResponse(operation, new HttpClientErrorException(
        HttpStatus.CONFLICT, "Conflict",
        KEYCLOAK_EMAIL_CONFLICT.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
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
