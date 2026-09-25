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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.UpdateUserRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.UserInfo;
import se.swedenconnect.iam.admin.keycloak.model.UserRight;
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.iam.commons.types.OrganizationID;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that {@code GET /api/users/{userId}} and {@code PUT /api/users/{userId}} only let a caller
 * address a user that is visible to them, and that a user outside the caller's scope cannot be told
 * apart from a UUID that does not exist.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SingleUserScopeTest {

  private static final String ICA = "5565552014";
  private static final String DIGG = "2021006883";

  private static final String WALLETREG = "walletreg";
  private static final String SIGNSERVICE = "signservice";

  private static final String CALLER = "caller-uuid";
  private static final String TARGET = "target-uuid";
  private static final String UNKNOWN = "unknown-uuid";

  @Mock
  private KeycloakAdminClient keycloakAdminClient;

  @Mock
  private HttpServletRequest request;

  @Mock
  private HttpSession session;

  private UserController controller;

  @BeforeEach
  void setUp() {
    this.controller = new UserController(this.keycloakAdminClient, new IamAdminProperties());
    when(this.request.getSession(false)).thenReturn(this.session);
    setupCallerAuth();

    when(this.keycloakAdminClient.fetchUserById(TARGET)).thenReturn(Optional.of(userInfo(TARGET)));
    when(this.keycloakAdminClient.fetchUserById(UNKNOWN)).thenReturn(Optional.empty());
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  // ---------------------------------------------------------------------------
  // Outside the caller's scope
  // ---------------------------------------------------------------------------

  /**
   * A user holding no right in any organization the caller administers is reported exactly as an
   * unknown UUID is, so the response never confirms that the UUID belongs to a user.
   */
  @Test
  void getUser_outsideCallerScope_isIndistinguishableFromUnknownUuid() {
    this.setupSession(Set.of(ICA), orgEntry(ICA, "admin"));
    this.setupRights(TARGET, new UserRight(DIGG, null, "admin"));

    final ResponseEntity<?> outOfScope = this.controller.getUser(TARGET, this.request);
    final ResponseEntity<?> unknown = this.controller.getUser(UNKNOWN, this.request);

    assertThat(outOfScope.getStatusCode().value()).isEqualTo(404);
    assertThat(outOfScope.getStatusCode()).isEqualTo(unknown.getStatusCode());
    assertThat(outOfScope.getBody()).isEqualTo(unknown.getBody());
    assertThat(outOfScope.getHeaders()).isEqualTo(unknown.getHeaders());
  }

  /** The same holds for the update, and the user is left untouched. */
  @Test
  void updateUser_outsideCallerScope_isIndistinguishableFromUnknownUuidAndChangesNothing() {
    this.setupSession(Set.of(ICA), orgEntry(ICA, "admin"));
    this.setupRights(TARGET, new UserRight(DIGG, null, "admin"));

    final ResponseEntity<?> outOfScope = this.controller.updateUser(
        TARGET, new UpdateUserRequest("New Name", "new@example.com", null), this.request);
    final ResponseEntity<?> unknown = this.controller.updateUser(
        UNKNOWN, new UpdateUserRequest("New Name", "new@example.com", null), this.request);

    assertThat(outOfScope.getStatusCode().value()).isEqualTo(404);
    assertThat(outOfScope.getStatusCode()).isEqualTo(unknown.getStatusCode());
    assertThat(outOfScope.getBody()).isEqualTo(unknown.getBody());
    verify(this.keycloakAdminClient, never()).updateUser(anyString(), anyString(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // Within the caller's scope
  // ---------------------------------------------------------------------------

  /** A user visible to the caller is read exactly as before. */
  @Test
  void getUser_visibleToCaller_returnsTheUser() {
    this.setupSession(Set.of(ICA), orgEntry(ICA, "admin"));
    this.setupRights(TARGET, new UserRight(ICA, null, "read"));

    final ResponseEntity<?> response = this.controller.getUser(TARGET, this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
  }

  /** And updated exactly as before. */
  @Test
  void updateUser_visibleToCaller_updatesTheUser() {
    this.setupSession(Set.of(ICA), orgEntry(ICA, "admin"));
    this.setupRights(TARGET, new UserRight(ICA, null, "read"));

    final ResponseEntity<?> response = this.controller.updateUser(
        TARGET, new UpdateUserRequest("New Name", "new@example.com", null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    verify(this.keycloakAdminClient).updateUser(TARGET, "New Name", "new@example.com", null);
  }

  /**
   * A right granted moments ago makes the user addressable in the same interaction, which is what
   * adding a user to an organization relies on.
   */
  @Test
  void getUser_rightGrantedJustNow_bringsTheUserIntoScope() {
    this.setupSession(Set.of(ICA), orgEntry(ICA, "admin"));
    this.setupRights(TARGET, new UserRight(DIGG, null, "admin"));

    assertThat(this.controller.getUser(TARGET, this.request).getStatusCode().value()).isEqualTo(404);

    // The caller grants a right in an organization they administer.
    this.setupRights(TARGET, new UserRight(DIGG, null, "admin"), new UserRight(ICA, null, "read"));

    assertThat(this.controller.getUser(TARGET, this.request).getStatusCode().value()).isEqualTo(200);
  }

  // ---------------------------------------------------------------------------
  // Function-scoped caller
  // ---------------------------------------------------------------------------

  /** A caller administering one function reaches the users holding rights on that function. */
  @Test
  void getUser_functionAdmin_reachesUserOnThatFunction() {
    this.setupFunctionAdminSession();
    this.setupRights(TARGET, new UserRight(ICA, WALLETREG, "write"));

    assertThat(this.controller.getUser(TARGET, this.request).getStatusCode().value()).isEqualTo(200);
  }

  /** And the users holding an organization-level right, which covers their function as well. */
  @Test
  void getUser_functionAdmin_reachesUserWithOrgLevelRight() {
    this.setupFunctionAdminSession();
    this.setupRights(TARGET, new UserRight(ICA, null, "read"));

    assertThat(this.controller.getUser(TARGET, this.request).getStatusCode().value()).isEqualTo(200);
  }

  /** But nobody whose only rights are on the organization's other functions. */
  @Test
  void updateUser_functionAdmin_doesNotReachUserOnAnotherFunction() {
    this.setupFunctionAdminSession();
    this.setupRights(TARGET, new UserRight(ICA, SIGNSERVICE, "admin"));

    final ResponseEntity<?> response = this.controller.updateUser(
        TARGET, new UpdateUserRequest("New Name", null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    verify(this.keycloakAdminClient, never()).updateUser(anyString(), anyString(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // Superuser
  // ---------------------------------------------------------------------------

  /** A superuser reaches every user, whatever rights they hold. */
  @Test
  void getAndUpdateUser_superuser_reachesEveryUser() {
    final AdminSessionData data = new AdminSessionData(true, null, null, List.of(), Set.of(),
        new OrgRightsClaim(true, List.of()));
    when(this.session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)).thenReturn(data);
    this.setupRights(TARGET, new UserRight(DIGG, null, "admin"));

    assertThat(this.controller.getUser(TARGET, this.request).getStatusCode().value()).isEqualTo(200);

    final ResponseEntity<?> updated = this.controller.updateUser(
        TARGET, new UpdateUserRequest("New Name", null, null), this.request);

    assertThat(updated.getStatusCode().value()).isEqualTo(200);
    verify(this.keycloakAdminClient).updateUser(TARGET, "New Name", null, null);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void setupSession(final Set<String> adminOrgs, final OrgRightsClaim.OrgEntry... entries) {
    final AdminSessionData data = new AdminSessionData(false, null, null, List.of(), adminOrgs,
        new OrgRightsClaim(false, List.of(entries)));
    when(this.session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)).thenReturn(data);
  }

  /** A caller who administers the {@code walletreg} function of ICA, and nothing else. */
  private void setupFunctionAdminSession() {
    this.setupSession(Set.of(ICA),
        orgEntry(ICA, null, new OrgRightsClaim.FunctionEntry(WALLETREG, "admin")));
  }

  private void setupRights(final String userId, final UserRight... rights) {
    when(this.keycloakAdminClient.fetchUserRights(userId)).thenReturn(List.of(rights));
  }

  private static void setupCallerAuth() {
    final OidcIdToken idToken = new OidcIdToken("token", Instant.now(),
        Instant.now().plusSeconds(3600), Map.of("sub", CALLER));
    final OidcUser principal = new DefaultOidcUser(
        List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken);
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  private static UserInfo userInfo(final String userId) {
    return new UserInfo(userId, userId, "Klara", "Karlsson", "klara@example.com", null, null, null,
        false, List.of());
  }

  private static OrgRightsClaim.OrgEntry orgEntry(final String orgId, final String orgLevelRight,
      final OrgRightsClaim.FunctionEntry... functions) {
    return new OrgRightsClaim.OrgEntry(
        OrganizationID.of(orgId), "Test Org", new LocalizedString(), orgLevelRight,
        List.of(functions));
  }

}
