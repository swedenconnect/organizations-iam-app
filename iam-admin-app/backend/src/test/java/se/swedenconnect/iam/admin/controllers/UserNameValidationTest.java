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
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.CreateUserRequest;
import se.swedenconnect.iam.admin.controllers.dto.UpdateUserRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.UserInfo;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that a name the realm's user profile would refuse is refused here, with a 400 saying
 * which characters are at fault, rather than reaching Keycloak and coming back as a server
 * error. See
 * <a href="https://github.com/swedenconnect/organizations-iam-app/issues/136">issue #136</a>.
 *
 * @author Per Fredrik Plars
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserNameValidationTest {

  /** The name from the issue. */
  private static final String MARKUP_NAME = "E2E XSS <img src=x onerror=\"alert(1)\"> abc";

  private static final String EMAIL = "e2e-abc@example.org";
  private static final String PIN = "196911292032";
  private static final String CALLER = "caller-uuid";
  private static final String TARGET = "target-uuid";

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
    when(this.keycloakAdminClient.findUserIdByEmail(anyString())).thenReturn(Optional.empty());
    when(this.keycloakAdminClient.usernameExists(anyString())).thenReturn(false);
    when(this.keycloakAdminClient.fetchUserById(TARGET)).thenReturn(Optional.of(userInfo()));
    when(this.keycloakAdminClient.fetchUserRights(anyString())).thenReturn(List.of());
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  /** The request from the issue: a 400 naming the problem, and nothing written. */
  @Test
  void create_nameWithMarkup_returns400AndWritesNothing() {
    final ResponseEntity<?> response = this.controller.createUser(
        new CreateUserRequest(MARKUP_NAME, EMAIL, null, PIN, null, null, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).asString().contains("name must not contain");
    verify(this.keycloakAdminClient, never())
        .createUser(any(), anyString(), any(), any(), any(), any(), any(), any(), any());
  }

  /** A name that carries no prohibited character is created as before. */
  @Test
  void create_ordinaryName_isCreated() {
    when(this.keycloakAdminClient.createUser(any(), anyString(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn("created-uuid");

    final ResponseEntity<?> response = this.controller.createUser(
        new CreateUserRequest("Anna-Maria O'Brien", EMAIL, null, PIN, null, null, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
  }

  /** The same name is refused on an update, and the user is left as they were. */
  @Test
  void update_nameWithMarkup_returns400AndWritesNothing() {
    final ResponseEntity<?> response = this.controller.updateUser(
        TARGET, new UpdateUserRequest(MARKUP_NAME, EMAIL, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).asString().contains("name must not contain");
    verify(this.keycloakAdminClient, never()).updateUser(anyString(), anyString(), any(), any());
  }

  /** A blank name is still reported as blank, not as a character problem. */
  @Test
  void create_blankName_isStillReportedAsBlank() {
    final ResponseEntity<?> response = this.controller.createUser(
        new CreateUserRequest("  ", EMAIL, null, PIN, null, null, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).asString().contains("must not be blank");
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
