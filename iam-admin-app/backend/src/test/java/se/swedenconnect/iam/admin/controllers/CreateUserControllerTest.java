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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.CreateUserRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;

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
 * Tests {@code POST /api/users} against the {@code iam.admin.user-registration} settings: which
 * request values that are honoured, how the input is validated, how duplicates are reported, and
 * what is written to Keycloak.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateUserControllerTest {

  private static final String NAME = "Martin Lindström";
  private static final String EMAIL = "martin@example.com";
  private static final String PIN = "196911292032";
  private static final String ORG_AFFILIATION = "martin@2021006883";
  private static final String CREATED_ID = "created-uuid";

  @Mock
  private KeycloakAdminClient keycloakAdminClient;

  @Mock
  private HttpServletRequest request;

  @Mock
  private HttpSession session;

  private IamAdminProperties properties;

  private UserController controller;

  @BeforeEach
  void setUp() {
    this.properties = new IamAdminProperties();
    this.controller = new UserController(this.keycloakAdminClient, this.properties);

    when(this.request.getSession(false)).thenReturn(this.session);
    final AdminSessionData data = new AdminSessionData(true, null, null, List.of(), Set.of(),
        new OrgRightsClaim(true, List.of()));
    when(this.session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)).thenReturn(data);

    when(this.keycloakAdminClient.findUserIdByPersonalIdentityNumber(anyString()))
        .thenReturn(Optional.empty());
    when(this.keycloakAdminClient.findUserIdByOrgAffiliation(anyString())).thenReturn(Optional.empty());
    when(this.keycloakAdminClient.usernameExists(anyString())).thenReturn(false);
    when(this.keycloakAdminClient.createUser(any(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(CREATED_ID);
  }

  // ---------------------------------------------------------------------------
  // Default settings
  // ---------------------------------------------------------------------------

  /** With the default settings a personal identity number is enough, and gives a UUID username. */
  @Test
  void create_defaultSettings_createsUserWithRandomUsername() {
    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, EMAIL, null, PIN, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.keycloakAdminClient).createUser(null, NAME, EMAIL, PIN, null, null, null);
  }

  /** No eID attribute at all is refused when eid-attribute-required is set, which it is by default. */
  @Test
  void create_noEidAttribute_returns400() {
    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, EMAIL, null, null, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    verify(this.keycloakAdminClient, never())
        .createUser(any(), anyString(), any(), any(), any(), any(), any());
  }

  /** With the requirement lifted, a user without any eID attribute is created. */
  @Test
  void create_noEidAttributeNotRequired_returns201() {
    this.properties.getUserRegistration().setEidAttributeRequired(false);

    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, EMAIL, null, null, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.keycloakAdminClient).createUser(null, NAME, EMAIL, null, null, null, null);
  }

  /** A blank name is refused, as before. */
  @Test
  void create_blankName_returns400() {
    final ResponseEntity<?> response = this.controller.createUser(
        request(" ", EMAIL, null, PIN, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
  }

  /** The personal identity number is validated for format when it is given. */
  @Test
  void create_malformedPersonalIdentityNumber_returns400() {
    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, EMAIL, null, "19691129", null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    verify(this.keycloakAdminClient, never())
        .createUser(any(), anyString(), any(), any(), any(), any(), any());
  }

  /** An email address without an '@' is refused. */
  @Test
  void create_malformedEmail_returns400() {
    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, "not-an-email", null, PIN, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
  }

  // ---------------------------------------------------------------------------
  // allow-select-user-id
  // ---------------------------------------------------------------------------

  /** With the setting off, a user ID in the request has no effect. */
  @Test
  void create_userIdIgnoredWhenNotAllowed() {
    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, EMAIL, "martin", PIN, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.keycloakAdminClient).createUser(null, NAME, EMAIL, PIN, null, null, null);
    verify(this.keycloakAdminClient, never()).usernameExists(anyString());
  }

  /** With the setting on, the chosen user ID becomes the Keycloak username. */
  @Test
  void create_userIdHonouredWhenAllowed() {
    this.properties.getUserRegistration().setAllowSelectUserId(true);

    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, EMAIL, "martin", PIN, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.keycloakAdminClient).createUser("martin", NAME, EMAIL, PIN, null, null, null);
  }

  /** A user ID that is already taken is refused, and no existing user is offered. */
  @Test
  void create_userIdTaken_returns409WithReason() {
    this.properties.getUserRegistration().setAllowSelectUserId(true);
    when(this.keycloakAdminClient.usernameExists("martin")).thenReturn(true);

    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, EMAIL, "martin", PIN, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody()).isEqualTo(Map.of("reason", "USER_ID_TAKEN"));
    verify(this.keycloakAdminClient, never())
        .createUser(any(), anyString(), any(), any(), any(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // allow-temporary-password
  // ---------------------------------------------------------------------------

  /** A temporary password is ignored unless the setting is on. */
  @Test
  void create_temporaryPasswordIgnoredWhenNotAllowed() {
    this.properties.getUserRegistration().setAllowSelectUserId(true);

    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, EMAIL, "martin", PIN, null, null, "secret"), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.keycloakAdminClient).createUser("martin", NAME, EMAIL, PIN, null, null, null);
  }

  /** With both settings on, the password is passed on to Keycloak. */
  @Test
  void create_temporaryPasswordHonouredWhenAllowed() {
    this.properties.getUserRegistration().setAllowSelectUserId(true);
    this.properties.getUserRegistration().setAllowTemporaryPassword(true);

    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, EMAIL, "martin", PIN, null, null, "secret"), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.keycloakAdminClient).createUser("martin", NAME, EMAIL, PIN, null, null, "secret");
  }

  // ---------------------------------------------------------------------------
  // eID attributes
  // ---------------------------------------------------------------------------

  /** A personal identity number is ignored when the setting offering it is off. */
  @Test
  void create_personalIdentityNumberIgnoredWhenDisabled() {
    this.properties.getUserRegistration().setPersonalNumberEnabled(false);
    this.properties.getUserRegistration().setOrgAffiliationEnabled(true);

    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, EMAIL, null, PIN, ORG_AFFILIATION, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.keycloakAdminClient).createUser(null, NAME, EMAIL, null, ORG_AFFILIATION, null, null);
  }

  /** An orgAffiliation is ignored, and does not satisfy the requirement, when it is not enabled. */
  @Test
  void create_orgAffiliationIgnoredWhenDisabled() {
    this.properties.getUserRegistration().setPersonalNumberEnabled(false);
    this.properties.getUserRegistration().setHsaIdEnabled(true);

    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, EMAIL, null, null, ORG_AFFILIATION, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    verify(this.keycloakAdminClient, never())
        .createUser(any(), anyString(), any(), any(), any(), any(), any());
  }

  /** An orgAffiliation alone satisfies the requirement. */
  @Test
  void create_orgAffiliationOnly_returns201() {
    this.properties.getUserRegistration().setOrgAffiliationEnabled(true);

    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, EMAIL, null, null, ORG_AFFILIATION, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.keycloakAdminClient).createUser(null, NAME, EMAIL, null, ORG_AFFILIATION, null, null);
  }

  /** The orgAffiliation format is validated: a local part, a single '@' and ten digits. */
  @Test
  void create_malformedOrgAffiliation_returns400() {
    this.properties.getUserRegistration().setOrgAffiliationEnabled(true);

    for (final String bad : List.of("martin@202100688", "martin@20210068833", "@2021006883",
        "martin2021006883", "mar@tin@2021006883", "martin@202100688X")) {
      final ResponseEntity<?> response = this.controller.createUser(
          request(NAME, EMAIL, null, null, bad, null, null), this.request);
      assertThat(response.getStatusCode().value()).as("orgAffiliation '%s'", bad).isEqualTo(400);
    }
    verify(this.keycloakAdminClient, never())
        .createUser(any(), anyString(), any(), any(), any(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // Duplicates
  // ---------------------------------------------------------------------------

  /** An existing user with the same personal identity number is reported with their ID. */
  @Test
  void create_duplicatePersonalIdentityNumber_returns409WithExistingUser() {
    when(this.keycloakAdminClient.findUserIdByPersonalIdentityNumber(PIN))
        .thenReturn(Optional.of("existing-uuid"));

    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, EMAIL, null, PIN, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody()).isEqualTo(Map.of("existingUserId", "existing-uuid"));
    verify(this.keycloakAdminClient, never())
        .createUser(any(), anyString(), any(), any(), any(), any(), any());
  }

  /** The same treatment for an existing orgAffiliation. */
  @Test
  void create_duplicateOrgAffiliation_returns409WithExistingUser() {
    this.properties.getUserRegistration().setOrgAffiliationEnabled(true);
    when(this.keycloakAdminClient.findUserIdByOrgAffiliation(ORG_AFFILIATION))
        .thenReturn(Optional.of("existing-uuid"));

    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, EMAIL, null, null, ORG_AFFILIATION, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody()).isEqualTo(Map.of("existingUserId", "existing-uuid"));
    verify(this.keycloakAdminClient, never())
        .createUser(any(), anyString(), any(), any(), any(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // Response
  // ---------------------------------------------------------------------------

  /** The response carries back the identity values that were written. */
  @Test
  @SuppressWarnings("unchecked")
  void create_responseCarriesWrittenValues() {
    this.properties.getUserRegistration().setAllowSelectUserId(true);
    this.properties.getUserRegistration().setOrgAffiliationEnabled(true);

    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, EMAIL, "martin", PIN, ORG_AFFILIATION, "+46701234567", null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    final Map<String, String> body = (Map<String, String>) response.getBody();
    assertThat(body).containsEntry("id", CREATED_ID)
        .containsEntry("name", NAME)
        .containsEntry("email", EMAIL)
        .containsEntry("userId", "martin")
        .containsEntry("personalIdentityNumber", PIN)
        .containsEntry("orgAffiliation", ORG_AFFILIATION)
        .containsEntry("phoneNumber", "+46701234567");
  }

  /** Blank values are treated as absent, not as values to write. */
  @Test
  void create_blankValuesTreatedAsAbsent() {
    this.properties.getUserRegistration().setAllowSelectUserId(true);
    this.properties.getUserRegistration().setAllowTemporaryPassword(true);
    this.properties.getUserRegistration().setOrgAffiliationEnabled(true);
    this.properties.getUserRegistration().setEidAttributeRequired(false);

    final ResponseEntity<?> response = this.controller.createUser(
        request(NAME, EMAIL, "  ", "  ", "  ", null, "  "), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    final ArgumentCaptor<String> userId = ArgumentCaptor.forClass(String.class);
    verify(this.keycloakAdminClient).createUser(
        userId.capture(), anyString(), any(), any(), any(), any(), any());
    assertThat(userId.getValue()).isNull();
    verify(this.keycloakAdminClient).createUser(null, NAME, EMAIL, null, null, null, null);
  }

  private static CreateUserRequest request(final String name, final String email, final String userId,
      final String pin, final String orgAffiliation, final String phoneNumber,
      final String temporaryPassword) {
    return new CreateUserRequest(name, email, userId, pin, orgAffiliation, phoneNumber, temporaryPassword);
  }

}
