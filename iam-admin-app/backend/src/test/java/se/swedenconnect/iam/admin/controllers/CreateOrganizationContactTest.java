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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import se.swedenconnect.iam.admin.controllers.dto.CreateOrganizationRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.service.OrganizationService;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that an organization can be created with contact details, so that the contact information
 * is in place from the moment the organization exists rather than after a follow-up update.
 *
 * <p>Both values are optional. A blank one is the same as an absent one: nothing is stored, which
 * is the outcome the update path gives for a blank value as well.</p>
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateOrganizationContactTest {

  private static final String ORG = "2021006883";

  private static final String LEGAL_NAME = "Myndigheten för digital förvaltning";

  private static final String EMAIL = "kontakt@digg.se";

  private static final String PHONE = "+46771114400";

  @Mock
  private OrganizationService organizationService;

  @Mock
  private HttpServletRequest request;

  @Mock
  private HttpSession session;

  private OrganizationController controller;

  @BeforeEach
  void setUp() {
    this.controller = new OrganizationController(this.organizationService);
    when(this.request.getSession(false)).thenReturn(this.session);
    when(this.organizationService.exists(ORG)).thenReturn(false);
    setupSuperuserSession();
  }

  @Test
  @DisplayName("Both contact values are passed to the creation")
  void bothValuesPassedThrough() {
    final ResponseEntity<?> response = this.controller.createOrganization(
        new CreateOrganizationRequest(ORG, LEGAL_NAME, null, null, EMAIL, PHONE), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.organizationService).create(ORG, LEGAL_NAME, null, null, EMAIL, PHONE);
  }

  @Test
  @DisplayName("Either value may be given on its own")
  void eitherValueOnItsOwn() {
    this.controller.createOrganization(
        new CreateOrganizationRequest(ORG, LEGAL_NAME, null, null, EMAIL, null), this.request);
    verify(this.organizationService).create(ORG, LEGAL_NAME, null, null, EMAIL, null);

    this.controller.createOrganization(
        new CreateOrganizationRequest(ORG, LEGAL_NAME, null, null, null, PHONE), this.request);
    verify(this.organizationService).create(ORG, LEGAL_NAME, null, null, null, PHONE);
  }

  @Test
  @DisplayName("Absent contact details create the organization without them")
  void absentValuesStoreNothing() {
    final ResponseEntity<?> response = this.controller.createOrganization(
        new CreateOrganizationRequest(ORG, LEGAL_NAME, null, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.organizationService).create(ORG, LEGAL_NAME, null, null, null, null);
  }

  @Test
  @DisplayName("A blank contact value is the same as an absent one")
  void blankValuesAreTreatedAsAbsent() {
    this.controller.createOrganization(
        new CreateOrganizationRequest(ORG, LEGAL_NAME, null, null, "   ", ""), this.request);

    verify(this.organizationService).create(ORG, LEGAL_NAME, null, null, null, null);
  }

  @Test
  @DisplayName("The contact details are echoed in the created response")
  void responseCarriesTheContactDetails() {
    final ResponseEntity<?> response = this.controller.createOrganization(
        new CreateOrganizationRequest(ORG, LEGAL_NAME, null, null, EMAIL, PHONE), this.request);

    assertThat(response.getBody()).isInstanceOf(Map.class);
    final Map<?, ?> body = (Map<?, ?>) response.getBody();
    assertThat(body.get("contactEmail")).isEqualTo(EMAIL);
    assertThat(body.get("contactPhone")).isEqualTo(PHONE);
  }

  @Test
  @DisplayName("Contact details do not make an otherwise invalid request succeed")
  void contactDetailsDoNotBypassValidation() {
    final ResponseEntity<?> blankName = this.controller.createOrganization(
        new CreateOrganizationRequest(ORG, "  ", null, null, EMAIL, PHONE), this.request);
    assertThat(blankName.getStatusCode().value()).isEqualTo(400);

    final ResponseEntity<?> badNumber = this.controller.createOrganization(
        new CreateOrganizationRequest("123", LEGAL_NAME, null, null, EMAIL, PHONE), this.request);
    assertThat(badNumber.getStatusCode().value()).isEqualTo(400);

    verify(this.organizationService, never())
        .create(anyString(), anyString(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Creating with contact details stays superuser-only")
  void nonSuperuserIsStillRejected() {
    setupNonSuperuserSession();

    final ResponseEntity<?> response = this.controller.createOrganization(
        new CreateOrganizationRequest(ORG, LEGAL_NAME, null, null, EMAIL, PHONE), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    verify(this.organizationService, never())
        .create(anyString(), anyString(), any(), any(), any(), any());
  }

  private void setupSuperuserSession() {
    setupSession(true);
  }

  private void setupNonSuperuserSession() {
    setupSession(false);
  }

  private void setupSession(final boolean superuser) {
    final AdminSessionData data = new AdminSessionData(
        superuser, null, null, List.of(), Set.of(),
        new OrgRightsClaim(superuser, List.of()));
    when(this.session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)).thenReturn(data);
  }
}
