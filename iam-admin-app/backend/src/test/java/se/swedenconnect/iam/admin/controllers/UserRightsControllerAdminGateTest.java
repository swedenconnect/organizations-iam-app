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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.AddUserRightRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.UserRight;
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.iam.commons.types.OrganizationID;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the authorization gates of {@link UserRightsController}: granting rights at the
 * organization level requires {@code org_level_right=admin}, which is strictly more than being an
 * admin of a function within the organization.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserRightsControllerAdminGateTest {

  private static final String ORG = "5590026042";
  private static final String FUNC = "demo";
  private static final String CALLER_ID = "caller-uuid";
  private static final String TARGET_ID = "target-uuid";

  @Mock
  private KeycloakAdminClient keycloakAdminClient;

  @Mock
  private HttpServletRequest request;

  @Mock
  private HttpSession session;

  private IamAdminProperties properties;

  private UserRightsController controller;

  @BeforeEach
  void setUp() {
    this.properties = new IamAdminProperties();
    this.properties.setAllowOrgRights(true);
    // The admin-assigning-admin gate is exercised by its own tests below; the cases covering the
    // org-level/function-level gates predate it and run with the restriction lifted.
    this.properties.setAllowAdminAssigningAdmin(true);
    this.controller = new UserRightsController(this.keycloakAdminClient, this.properties);

    when(this.request.getSession(false)).thenReturn(this.session);

    final OidcUser caller = mock(OidcUser.class);
    when(caller.getSubject()).thenReturn(CALLER_ID);
    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken(caller, "n/a"));

    when(this.keycloakAdminClient.organizationExists(ORG)).thenReturn(true);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  /**
   * A caller who is admin of a function within the organization — but was not granted admin at the
   * organization level — must not be able to grant org-level rights.
   */
  @Test
  void addUserToOrg_functionAdminOnly_returns403() {
    setupSession(orgEntry(ORG, null, new OrgRightsClaim.FunctionEntry(FUNC, "admin")));

    final ResponseEntity<?> response = this.controller.addUserToOrg(
        ORG, TARGET_ID, new AddUserRightRequest("read"), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    verify(this.keycloakAdminClient, never()).addUserToOrgRight(anyString(), anyString(), anyString());
  }

  /** A caller with admin at the organization level may grant org-level rights. */
  @Test
  void addUserToOrg_orgLevelAdmin_returns204() {
    setupSession(orgEntry(ORG, "admin", new OrgRightsClaim.FunctionEntry(FUNC, "admin")));

    final ResponseEntity<?> response = this.controller.addUserToOrg(
        ORG, TARGET_ID, new AddUserRightRequest("admin"), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    verify(this.keycloakAdminClient).addUserToOrgRight(ORG, TARGET_ID, "admin");
  }

  /**
   * Org-level admin on an organization with no attached functions still permits granting org-level
   * rights — the empty functions array does not weaken the org-level gate.
   */
  @Test
  void addUserToOrg_orgLevelAdminWithNoAttachedFunctions_returns204() {
    setupSession(orgEntry(ORG, "admin"));

    final ResponseEntity<?> response = this.controller.addUserToOrg(
        ORG, TARGET_ID, new AddUserRightRequest("admin"), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    verify(this.keycloakAdminClient).addUserToOrgRight(ORG, TARGET_ID, "admin");
  }

  /** Function-level grants still work for a function admin. */
  @Test
  void addUserToFunction_functionAdmin_returns204() {
    setupSession(orgEntry(ORG, null, new OrgRightsClaim.FunctionEntry(FUNC, "admin")));
    when(this.keycloakAdminClient.isFunctionAttachedToOrg(ORG, FUNC)).thenReturn(true);

    final ResponseEntity<?> response = this.controller.addUserToFunction(
        ORG, FUNC, TARGET_ID, new AddUserRightRequest("admin"), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    verify(this.keycloakAdminClient).addUserToFunctionRight(ORG, FUNC, TARGET_ID, "admin");
  }

  /**
   * A caller holding org-level admin on one organization may not grant org-level rights in a
   * different organization.
   */
  @Test
  void addUserToOrg_orgLevelAdminOnDifferentOrg_returns403() {
    setupSession(orgEntry("5561234567", "admin"));

    final ResponseEntity<?> response = this.controller.addUserToOrg(
        ORG, TARGET_ID, new AddUserRightRequest("read"), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    verify(this.keycloakAdminClient, never()).addUserToOrgRight(anyString(), anyString(), anyString());
  }

  // ---------------------------------------------------------------------------
  // iam.admin.allow-admin-assigning-admin
  // ---------------------------------------------------------------------------

  /** With the setting false, an org admin who is not a superuser may not grant org-level admin. */
  @Test
  void addUserToOrg_grantAdminRestricted_returns403() {
    this.properties.setAllowAdminAssigningAdmin(false);
    setupSession(orgEntry(ORG, "admin"));

    final ResponseEntity<?> response = this.controller.addUserToOrg(
        ORG, TARGET_ID, new AddUserRightRequest("admin"), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    verify(this.keycloakAdminClient, never()).addUserToOrgRight(anyString(), anyString(), anyString());
  }

  /** The same at the function level. */
  @Test
  void addUserToFunction_grantAdminRestricted_returns403() {
    this.properties.setAllowAdminAssigningAdmin(false);
    setupSession(orgEntry(ORG, null, new OrgRightsClaim.FunctionEntry(FUNC, "admin")));

    final ResponseEntity<?> response = this.controller.addUserToFunction(
        ORG, FUNC, TARGET_ID, new AddUserRightRequest("admin"), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    verify(this.keycloakAdminClient, never())
        .addUserToFunctionRight(anyString(), anyString(), anyString(), anyString());
  }

  /** Downgrading an existing org-level admin to a lesser right is equally forbidden. */
  @Test
  void addUserToOrg_downgradeExistingAdminRestricted_returns403() {
    this.properties.setAllowAdminAssigningAdmin(false);
    setupSession(orgEntry(ORG, "admin"));
    when(this.keycloakAdminClient.fetchUserRights(TARGET_ID))
        .thenReturn(List.of(new UserRight(ORG, null, "admin")));

    final ResponseEntity<?> response = this.controller.addUserToOrg(
        ORG, TARGET_ID, new AddUserRightRequest("write"), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    verify(this.keycloakAdminClient, never()).addUserToOrgRight(anyString(), anyString(), anyString());
  }

  /** Downgrading an existing function-level admin is forbidden too. */
  @Test
  void addUserToFunction_downgradeExistingAdminRestricted_returns403() {
    this.properties.setAllowAdminAssigningAdmin(false);
    setupSession(orgEntry(ORG, null, new OrgRightsClaim.FunctionEntry(FUNC, "admin")));
    when(this.keycloakAdminClient.fetchUserRights(TARGET_ID))
        .thenReturn(List.of(new UserRight(ORG, FUNC, "admin")));

    final ResponseEntity<?> response = this.controller.addUserToFunction(
        ORG, FUNC, TARGET_ID, new AddUserRightRequest("write"), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    verify(this.keycloakAdminClient, never())
        .addUserToFunctionRight(anyString(), anyString(), anyString(), anyString());
  }

  /** Granting write to a user who holds no admin right anywhere is unaffected by the setting. */
  @Test
  void addUserToOrg_grantWriteToNonAdminRestricted_returns204() {
    this.properties.setAllowAdminAssigningAdmin(false);
    setupSession(orgEntry(ORG, "admin"));
    when(this.keycloakAdminClient.fetchUserRights(TARGET_ID))
        .thenReturn(List.of(new UserRight(ORG, null, "read")));

    final ResponseEntity<?> response = this.controller.addUserToOrg(
        ORG, TARGET_ID, new AddUserRightRequest("write"), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    verify(this.keycloakAdminClient).addUserToOrgRight(ORG, TARGET_ID, "write");
  }

  /** Removing org-level admin is forbidden, and wins over the last-admin 409. */
  @Test
  void removeUserFromOrg_removeAdminRestricted_returns403() {
    this.properties.setAllowAdminAssigningAdmin(false);
    setupSession(orgEntry(ORG, "admin"));

    final ResponseEntity<?> response = this.controller.removeUserFromOrg(
        ORG, TARGET_ID, "admin", this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    verify(this.keycloakAdminClient, never()).removeUserFromOrgRight(anyString(), anyString(), anyString());
  }

  /** The same at the function level. */
  @Test
  void removeUserFromFunction_removeAdminRestricted_returns403() {
    this.properties.setAllowAdminAssigningAdmin(false);
    setupSession(orgEntry(ORG, null, new OrgRightsClaim.FunctionEntry(FUNC, "admin")));

    final ResponseEntity<?> response = this.controller.removeUserFromFunction(
        ORG, FUNC, TARGET_ID, "admin", this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    verify(this.keycloakAdminClient, never())
        .removeUserFromFunctionRight(anyString(), anyString(), anyString(), anyString());
  }

  /** Removing read or write is unaffected by the setting. */
  @Test
  void removeUserFromOrg_removeWriteRestricted_returns204() {
    this.properties.setAllowAdminAssigningAdmin(false);
    setupSession(orgEntry(ORG, "admin"));

    final ResponseEntity<?> response = this.controller.removeUserFromOrg(
        ORG, TARGET_ID, "write", this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    verify(this.keycloakAdminClient).removeUserFromOrgRight(ORG, TARGET_ID, "write");
  }

  /** The same at the function level. */
  @Test
  void removeUserFromFunction_removeReadRestricted_returns204() {
    this.properties.setAllowAdminAssigningAdmin(false);
    setupSession(orgEntry(ORG, null, new OrgRightsClaim.FunctionEntry(FUNC, "admin")));

    final ResponseEntity<?> response = this.controller.removeUserFromFunction(
        ORG, FUNC, TARGET_ID, "read", this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    verify(this.keycloakAdminClient).removeUserFromFunctionRight(ORG, FUNC, TARGET_ID, "read");
  }

  /** With the setting true, a non-superuser org admin may grant admin again. */
  @Test
  void addUserToOrg_grantAdminAllowed_returns204() {
    this.properties.setAllowAdminAssigningAdmin(true);
    setupSession(orgEntry(ORG, "admin"));

    final ResponseEntity<?> response = this.controller.addUserToOrg(
        ORG, TARGET_ID, new AddUserRightRequest("admin"), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    verify(this.keycloakAdminClient).addUserToOrgRight(ORG, TARGET_ID, "admin");
  }

  /** A superuser is never affected by the setting — granting admin still works. */
  @Test
  void addUserToOrg_superuserGrantAdminRestricted_returns204() {
    this.properties.setAllowAdminAssigningAdmin(false);
    setupSuperuserSession();

    final ResponseEntity<?> response = this.controller.addUserToOrg(
        ORG, TARGET_ID, new AddUserRightRequest("admin"), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    verify(this.keycloakAdminClient).addUserToOrgRight(ORG, TARGET_ID, "admin");
  }

  /** And a superuser may still remove admin, as long as another admin remains. */
  @Test
  void removeUserFromOrg_superuserRemoveAdminRestricted_returns204() {
    this.properties.setAllowAdminAssigningAdmin(false);
    setupSuperuserSession();
    when(this.keycloakAdminClient.hasOtherOrgAdmin(ORG, TARGET_ID)).thenReturn(true);

    final ResponseEntity<?> response = this.controller.removeUserFromOrg(
        ORG, TARGET_ID, "admin", this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    verify(this.keycloakAdminClient).removeUserFromOrgRight(ORG, TARGET_ID, "admin");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void setupSession(final OrgRightsClaim.OrgEntry... entries) {
    final AdminSessionData data = new AdminSessionData(false, null, null, List.of(), Set.of(ORG),
        new OrgRightsClaim(false, List.of(entries)));
    when(this.session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)).thenReturn(data);
  }

  private void setupSuperuserSession() {
    final AdminSessionData data = new AdminSessionData(true, null, null, List.of(), Set.of(),
        new OrgRightsClaim(true, List.of()));
    when(this.session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)).thenReturn(data);
  }

  private static OrgRightsClaim.OrgEntry orgEntry(final String orgId, final String orgLevelRight,
      final OrgRightsClaim.FunctionEntry... functions) {
    return new OrgRightsClaim.OrgEntry(
        OrganizationID.of(orgId), new LocalizedString(), orgLevelRight, List.of(functions));
  }
}
