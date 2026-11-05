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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import se.swedenconnect.iam.admin.audit.RecordingAuditPublisher;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.AddUserRightRequest;
import se.swedenconnect.iam.admin.controllers.dto.CreateUserRequest;
import se.swedenconnect.iam.admin.controllers.dto.UpdateUserRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.UserInfo;
import se.swedenconnect.iam.admin.keycloak.model.UserRight;
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.iam.commons.types.OrganizationID;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;
import se.swedenconnect.spring.audit.AuditEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static se.swedenconnect.iam.admin.audit.RecordingAuditPublisher.object;

/**
 * Tests the audit events of the user and user rights endpoints.
 *
 * @author Martin Lindström
 */
class UserAuditTest {

  private static final String ICA = "5565552014";
  private static final String DIGG = "2021006883";
  private static final String CALLER = "caller-uuid";
  private static final String TARGET = "target-uuid";

  private final RecordingAuditPublisher publisher = new RecordingAuditPublisher();
  private final KeycloakAdminClient keycloakAdminClient = mock(KeycloakAdminClient.class);
  private final IamAdminProperties properties = new IamAdminProperties();

  private UserController userController;
  private UserRightsController rightsController;
  private MockHttpServletRequest request;
  private MockHttpSession session;

  @BeforeEach
  void setUp() {
    this.properties.setAllowOrgRights(true);
    this.userController = new UserController(this.keycloakAdminClient, this.properties, this.publisher);
    this.rightsController = new UserRightsController(this.keycloakAdminClient, this.properties, this.publisher);
    this.session = new MockHttpSession();
    this.request = new MockHttpServletRequest();
    this.request.setSession(this.session);
    final OidcUser principal = new DefaultOidcUser(List.of(),
        new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(60), Map.of("sub", CALLER)));
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    when(this.keycloakAdminClient.fetchUserById(TARGET)).thenReturn(Optional.of(target()));
  }

  @AfterEach
  void cleanUp() {
    SecurityContextHolder.clearContext();
  }

  // Users

  @Test
  void createUser_userCreated() {
    this.superuser();
    this.properties.getUserRegistration().setPersonalNumberEnabled(true);
    this.properties.getUserRegistration().setAllowTemporaryPassword(true);
    when(this.keycloakAdminClient.createUser(any(), anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(TARGET);

    this.userController.createUser(new CreateUserRequest(
        "Klara Karlsson", null, null, "198001011234", null, null, null, null, "secret"), this.request);

    final AuditEvent event = this.publisher.single("user_created");
    assertThat(event.getPrincipal()).isEqualTo(CALLER);
    assertThat(object(event, "user")).containsExactly(
        Map.entry("user_id", TARGET),
        Map.entry("display_name", "Klara Karlsson"),
        Map.entry("personal_identity_number", "198001011234"),
        Map.entry("temporary_password_set", true));
  }

  /** The HSA-ID and EFOS-ID are recorded on {@code user_created} when their settings are on. */
  @Test
  void createUser_userCreatedWithHsaIdAndEfosId() {
    this.superuser();
    this.properties.getUserRegistration().setHsaIdEnabled(true);
    this.properties.getUserRegistration().setEfosIdEnabled(true);
    when(this.keycloakAdminClient.createUser(any(), anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(TARGET);

    this.userController.createUser(new CreateUserRequest(
        "Klara Karlsson", null, null, null, null, "SE2321000016-1234", "efos-4711", null, null), this.request);

    assertThat(object(this.publisher.single("user_created"), "user"))
        .containsEntry("hsa_id", "SE2321000016-1234")
        .containsEntry("efos_id", "efos-4711");
  }

  /** A 400 is not audited. */
  @Test
  void createUser_validationError_notAudited() {
    this.superuser();

    this.userController.createUser(new CreateUserRequest(" ", null, null, null, null, null, null, null, null), this.request);

    assertThat(this.publisher.events()).isEmpty();
  }

  @Test
  void createUser_noSession_accessDenied() {
    this.userController.createUser(new CreateUserRequest("A", null, null, null, null, null, null, null, null), this.request);

    assertThat(object(this.publisher.single("access_denied"), "operation"))
        .containsExactly(Map.entry("name", "create_user"), Map.entry("reason", "no_admin_session"));
  }

  @Test
  void updateUser_changedFields() {
    this.superuser();

    this.userController.updateUser(TARGET, new UpdateUserRequest("Klara Andersson", "klara@example.com", null),
        this.request);

    final AuditEvent event = this.publisher.single("user_updated");
    assertThat(object(event, "user")).containsExactly(
        Map.entry("user_id", TARGET), Map.entry("display_name", "Klara Andersson"));
    assertThat(object(event, "change")).containsExactly(Map.entry("changed_fields", List.of("name")));
  }

  /** A save that changes nothing produces no event. */
  @Test
  void updateUser_nothingChanged_noEvent() {
    this.superuser();

    final ResponseEntity<?> response = this.userController.updateUser(
        TARGET, new UpdateUserRequest("Klara Karlsson", "klara@example.com", ""), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(this.publisher.events()).isEmpty();
  }

  @Test
  void updateUser_self_accessDenied() {
    this.superuser();

    this.userController.updateUser(CALLER, new UpdateUserRequest("Me", null, null), this.request);

    assertThat(object(this.publisher.single("access_denied"), "operation"))
        .containsEntry("reason", "self_modification");
  }

  /** A user hidden behind a 404 for being out of scope is recorded, while the response is a plain 404. */
  @Test
  void getUser_outOfScope_accessDeniedAndPlain404() {
    this.regularAdmin(ICA);
    when(this.keycloakAdminClient.fetchUserRights(TARGET)).thenReturn(List.of(new UserRight(DIGG, null, "read")));

    final ResponseEntity<?> response = this.userController.getUser(TARGET, this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(response.getBody()).isNull();
    final AuditEvent event = this.publisher.single("access_denied");
    assertThat(object(event, "operation"))
        .containsExactly(Map.entry("name", "read_user"), Map.entry("reason", "out_of_scope"));
    assertThat(object(event, "user")).containsEntry("user_id", TARGET).containsEntry("display_name", "Klara Karlsson");
  }

  /** An unknown UUID is an ordinary 404 and is not audited. */
  @Test
  void getUser_unknown_notAudited() {
    this.regularAdmin(ICA);

    assertThat(this.userController.getUser("unknown", this.request).getStatusCode().value()).isEqualTo(404);
    assertThat(this.publisher.events()).isEmpty();
  }

  /** Reads are not audited. */
  @Test
  void reads_notAudited() {
    this.superuser();

    this.userController.getUser(TARGET, this.request);
    this.userController.listUsers(0, 10, this.request);

    assertThat(this.publisher.events()).isEmpty();
  }

  @Test
  void deleteUser_userDeletedWithRemovedRights() {
    this.superuser();
    when(this.keycloakAdminClient.fetchUserRights(TARGET)).thenReturn(List.of(new UserRight(ICA, "demo", "write")));

    this.userController.deleteUser(TARGET, this.request);

    final AuditEvent event = this.publisher.single("user_deleted");
    assertThat(object(event, "user")).containsExactly(
        Map.entry("user_id", TARGET),
        Map.entry("display_name", "Klara Karlsson"),
        Map.entry("personal_identity_number", "198001011234"));
    assertThat(event.getData().get("removed_rights")).isEqualTo(List.of(
        Map.of("organization_identifier", ICA, "function_id", "demo", "right", "write")));
  }

  @Test
  void deleteUser_userDeletedWithHsaIdAndEfosId() {
    this.superuser();
    when(this.keycloakAdminClient.fetchUserById(TARGET)).thenReturn(Optional.of(new UserInfo(TARGET, "klara",
        "Klara", "Karlsson", null, null, null, "SE2321000016-1234", "efos-4711", null, false, List.of())));

    this.userController.deleteUser(TARGET, this.request);

    assertThat(object(this.publisher.single("user_deleted"), "user")).containsExactly(
        Map.entry("user_id", TARGET),
        Map.entry("display_name", "Klara Karlsson"),
        Map.entry("hsa_id", "SE2321000016-1234"),
        Map.entry("efos_id", "efos-4711"));
  }

  @Test
  void deleteUser_lastAdmin_operationRejected() {
    this.superuser();
    when(this.keycloakAdminClient.fetchUserRights(TARGET)).thenReturn(List.of(new UserRight(ICA, null, "admin")));

    final ResponseEntity<?> response = this.userController.deleteUser(TARGET, this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    final AuditEvent event = this.publisher.single("operation_rejected");
    assertThat(object(event, "operation"))
        .containsExactly(Map.entry("name", "delete_user"), Map.entry("reason", "last_admin"));
    assertThat(event.getData()).containsEntry("organization_identifier", ICA).containsEntry("right", "admin");
  }

  @Test
  void deleteUser_notSuperuser_accessDenied() {
    this.regularAdmin(ICA);

    this.userController.deleteUser(TARGET, this.request);

    assertThat(object(this.publisher.single("access_denied"), "operation")).containsEntry("reason", "not_superuser");
  }

  @Test
  void deleteUser_keycloakError_operationFailed() {
    this.superuser();
    doThrow(new KeycloakAdminException("down")).when(this.keycloakAdminClient).deleteUser(TARGET);

    assertThat(this.userController.deleteUser(TARGET, this.request).getStatusCode().value()).isEqualTo(500);
    assertThat(this.publisher.types()).containsExactly("operation_failed");
  }

  /** A failed audit write gives the caller an error, although the change has been made. */
  @Test
  void deleteUser_auditWriteFailure_propagates() {
    this.superuser();
    this.publisher.failWritesOf("user_deleted");

    assertThatThrownBy(() -> this.userController.deleteUser(TARGET, this.request))
        .isInstanceOf(se.swedenconnect.spring.audit.repository.AuditEventWriteException.class);
  }

  // Rights

  @Test
  void assignRight_withPreviousRight() {
    this.superuser();
    when(this.keycloakAdminClient.organizationExists(ICA)).thenReturn(true);
    when(this.keycloakAdminClient.fetchUserRights(TARGET)).thenReturn(List.of(new UserRight(ICA, null, "read")));

    this.rightsController.addUserToOrg(ICA, TARGET, new AddUserRightRequest("write"), this.request);

    final AuditEvent event = this.publisher.single("user_right_assigned");
    assertThat(object(event, "user")).containsEntry("display_name", "Klara Karlsson");
    assertThat(event.getData())
        .containsEntry("organization_identifier", ICA)
        .containsEntry("right", "write")
        .containsEntry("previous_right", "read")
        .doesNotContainKey("function_id");
  }

  @Test
  void assignFunctionRight_new() {
    this.superuser();
    when(this.keycloakAdminClient.organizationExists(ICA)).thenReturn(true);
    when(this.keycloakAdminClient.isFunctionAttachedToOrg(ICA, "demo")).thenReturn(true);

    this.rightsController.addUserToFunction(ICA, "demo", TARGET, new AddUserRightRequest("read"), this.request);

    assertThat(this.publisher.single("user_right_assigned").getData())
        .containsEntry("function_id", "demo")
        .doesNotContainKey("previous_right");
  }

  /** Granting a right the user already holds changes nothing and is not audited. */
  @Test
  void assignRight_sameRight_noEvent() {
    this.superuser();
    when(this.keycloakAdminClient.organizationExists(ICA)).thenReturn(true);
    when(this.keycloakAdminClient.fetchUserRights(TARGET)).thenReturn(List.of(new UserRight(ICA, null, "read")));

    this.rightsController.addUserToOrg(ICA, TARGET, new AddUserRightRequest("read"), this.request);

    assertThat(this.publisher.events()).isEmpty();
  }

  @Test
  void assignRight_orgRightsDisabled_accessDenied() {
    this.superuser();
    this.properties.setAllowOrgRights(false);

    this.rightsController.addUserToOrg(ICA, TARGET, new AddUserRightRequest("read"), this.request);

    final AuditEvent event = this.publisher.single("access_denied");
    assertThat(object(event, "operation"))
        .containsExactly(Map.entry("name", "assign_user_right"), Map.entry("reason", "org_rights_disabled"));
    assertThat(event.getData()).containsEntry("organization_identifier", ICA).containsEntry("right", "read");
  }

  @Test
  void assignRight_adminRestricted_accessDenied() {
    this.regularAdmin(ICA);

    this.rightsController.addUserToOrg(ICA, TARGET, new AddUserRightRequest("admin"), this.request);

    assertThat(object(this.publisher.single("access_denied"), "operation"))
        .containsEntry("reason", "admin_assignment_restricted");
  }

  @Test
  void assignRight_notAdminOfOrganization_accessDenied() {
    this.regularAdmin(ICA);

    this.rightsController.addUserToOrg(DIGG, TARGET, new AddUserRightRequest("read"), this.request);

    assertThat(object(this.publisher.single("access_denied"), "operation"))
        .containsEntry("reason", "insufficient_rights");
  }

  @Test
  void assignRight_keycloakError_operationFailed() {
    this.superuser();
    when(this.keycloakAdminClient.organizationExists(ICA)).thenReturn(true);
    doThrow(new KeycloakAdminException("down"))
        .when(this.keycloakAdminClient).addUserToOrgRight(ICA, TARGET, "read");

    this.rightsController.addUserToOrg(ICA, TARGET, new AddUserRightRequest("read"), this.request);

    assertThat(this.publisher.types()).containsExactly("operation_failed");
  }

  @Test
  void removeRight_userRightRemoved() {
    this.superuser();
    when(this.keycloakAdminClient.fetchUserRights(TARGET)).thenReturn(List.of(new UserRight(ICA, "demo", "read")));

    this.rightsController.removeUserFromFunction(ICA, "demo", TARGET, "read", this.request);

    assertThat(this.publisher.single("user_right_removed").getData())
        .containsEntry("organization_identifier", ICA)
        .containsEntry("function_id", "demo")
        .containsEntry("right", "read");
  }

  /** Removing a right the user does not hold changes nothing and is not audited. */
  @Test
  void removeRight_notHeld_noEvent() {
    this.superuser();

    this.rightsController.removeUserFromOrg(ICA, TARGET, "read", this.request);

    assertThat(this.publisher.events()).isEmpty();
  }

  @Test
  void removeRight_lastAdmin_operationRejected() {
    this.superuser();
    when(this.keycloakAdminClient.hasOtherOrgAdmin(eq(ICA), anyString())).thenReturn(false);

    this.rightsController.removeUserFromOrg(ICA, TARGET, "admin", this.request);

    assertThat(object(this.publisher.single("operation_rejected"), "operation"))
        .containsExactly(Map.entry("name", "remove_user_right"), Map.entry("reason", "last_admin"));
  }

  @Test
  void removeRight_self_accessDenied() {
    this.superuser();

    this.rightsController.removeUserFromOrg(ICA, CALLER, "read", this.request);

    assertThat(object(this.publisher.single("access_denied"), "operation"))
        .containsEntry("reason", "self_modification");
  }

  // Helpers

  private void superuser() {
    this.session.setAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR, new AdminSessionData(
        true, null, null, List.of(), Set.of(), new OrgRightsClaim(true, List.of())));
  }

  private void regularAdmin(final String org) {
    this.session.setAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR, new AdminSessionData(
        false, null, null, List.of(), Set.of(org), new OrgRightsClaim(false, List.of(
        new OrgRightsClaim.OrgEntry(OrganizationID.of(org), "Org", new LocalizedString(), "admin", List.of())))));
  }

  private static UserInfo target() {
    return new UserInfo(TARGET, "klara", "Klara", "Karlsson", "klara@example.com", "198001011234", null, null, null, null,
        false, List.of());
  }

}
