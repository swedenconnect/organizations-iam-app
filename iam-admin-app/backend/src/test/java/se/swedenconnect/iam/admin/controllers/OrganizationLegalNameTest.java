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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import se.swedenconnect.iam.admin.controllers.dto.CreateOrganizationRequest;
import se.swedenconnect.iam.admin.controllers.dto.UpdateOrganizationRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;
import se.swedenconnect.iam.admin.service.OrganizationService;
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.iam.commons.types.OrganizationID;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the legal name and display name handling of {@link OrganizationController}: the legal name
 * is mandatory on create and cannot be emptied on update, display names are optional and are
 * removed when sent empty, and any name change remains reserved to superusers.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrganizationLegalNameTest {

  private static final String ORG = "2021006883";

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
    when(this.organizationService.update(anyString(), any(), any(), any(), any(), any()))
        .thenReturn(orgInfo("Myndigheten för Digital förvaltning", null, null));
  }

  // ---------------------------------------------------------------------------
  // Create
  // ---------------------------------------------------------------------------

  /** An organization may be created with a legal name and no display names at all. */
  @Test
  void create_legalNameOnly_returns201() {
    setupSuperuserSession();

    final ResponseEntity<?> response = this.controller.createOrganization(
        new CreateOrganizationRequest(ORG, "Myndigheten för Digital förvaltning", null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.organizationService).create(ORG, "Myndigheten för Digital förvaltning", null, null);
  }

  /** A blank display name is the same as none, and nothing is stored for it. */
  @Test
  void create_blankDisplayNames_storedAsAbsent() {
    setupSuperuserSession();

    this.controller.createOrganization(
        new CreateOrganizationRequest(ORG, "Myndigheten för Digital förvaltning", "  ", ""), this.request);

    verify(this.organizationService).create(ORG, "Myndigheten för Digital förvaltning", null, null);
  }

  /** Display names are passed through when given. */
  @Test
  void create_withDisplayNames_passedThrough() {
    setupSuperuserSession();

    this.controller.createOrganization(
        new CreateOrganizationRequest(ORG, "Myndigheten för Digital förvaltning",
            "Digg - Myndigheten för Digital förvaltning", "Digg - Authority for Digital Government"),
        this.request);

    verify(this.organizationService).create(ORG, "Myndigheten för Digital förvaltning",
        "Digg - Myndigheten för Digital förvaltning", "Digg - Authority for Digital Government");
  }

  /** Creating without a legal name is rejected. */
  @Test
  void create_blankLegalName_returns400() {
    setupSuperuserSession();

    final ResponseEntity<?> response = this.controller.createOrganization(
        new CreateOrganizationRequest(ORG, "   ", "Digg - Myndigheten för Digital förvaltning", null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    verify(this.organizationService, never()).create(anyString(), anyString(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // Update
  // ---------------------------------------------------------------------------

  /** Omitting the legal name leaves it unchanged. */
  @Test
  void update_legalNameOmitted_leftUnchanged() {
    setupSuperuserSession();

    final ResponseEntity<?> response = this.controller.updateOrganization(
        ORG, new UpdateOrganizationRequest(null, null, null, "a@b.se", null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    verify(this.organizationService).update(ORG, null, null, null, "a@b.se", null);
  }

  /** Sending the legal name blank is a validation error, since it is mandatory. */
  @Test
  void update_blankLegalName_returns400() {
    setupSuperuserSession();

    final ResponseEntity<?> response = this.controller.updateOrganization(
        ORG, new UpdateOrganizationRequest("  ", null, null, null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    verify(this.organizationService, never()).update(anyString(), any(), any(), any(), any(), any());
  }

  /** Sending a display name empty removes it, which the service layer is told by the empty string. */
  @Test
  void update_emptyDisplayName_isPassedThroughAsRemoval() {
    setupSuperuserSession();

    this.controller.updateOrganization(
        ORG, new UpdateOrganizationRequest(null, "", null, null, null), this.request);

    verify(this.organizationService).update(ORG, null, "", null, null, null);
  }

  /** A non-superuser sending any name field is refused, exactly as before this change. */
  @Test
  void update_nonSuperuserSendingLegalName_returns403() {
    setupOrgAdminSession();

    final ResponseEntity<?> response = this.controller.updateOrganization(
        ORG, new UpdateOrganizationRequest("Myndigheten för Digital förvaltning", null, null, null, null),
        this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    verify(this.organizationService, never()).update(anyString(), any(), any(), any(), any(), any());
  }

  /** The same for a display name. */
  @Test
  void update_nonSuperuserSendingDisplayName_returns403() {
    setupOrgAdminSession();

    final ResponseEntity<?> response = this.controller.updateOrganization(
        ORG, new UpdateOrganizationRequest(null, "Digg - Myndigheten för Digital förvaltning", null, null, null),
        this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    verify(this.organizationService, never()).update(anyString(), any(), any(), any(), any(), any());
  }

  /** A non-superuser org admin may still update contact fields, with no name touched. */
  @Test
  void update_nonSuperuserContactOnly_returns200() {
    setupOrgAdminSession();

    final ResponseEntity<?> response = this.controller.updateOrganization(
        ORG, new UpdateOrganizationRequest(null, null, null, "a@b.se", "0700000000"), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    verify(this.organizationService).update(
        eq(ORG), isNull(), isNull(), isNull(), eq("a@b.se"), eq("0700000000"));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static OrganizationInfo orgInfo(
      final String legalName, final String nameSv, final String nameEn) {
    LocalizedString displayName = null;
    if (nameSv != null || nameEn != null) {
      displayName = new LocalizedString();
      if (nameSv != null) {
        displayName.add("sv", nameSv);
      }
      if (nameEn != null) {
        displayName.add("en", nameEn);
      }
    }
    return new OrganizationInfo(ORG, legalName, displayName, "group-id", List.of(), null, null);
  }

  private void setupSuperuserSession() {
    setSession(new AdminSessionData(true, null, null, List.of(), Set.of(),
        new OrgRightsClaim(true, List.of())));
  }

  private void setupOrgAdminSession() {
    final OrgRightsClaim.OrgEntry entry = new OrgRightsClaim.OrgEntry(
        OrganizationID.of(ORG), "Myndigheten för Digital förvaltning", new LocalizedString(), "admin", List.of());
    setSession(new AdminSessionData(false, null, null, List.of(), Set.of(ORG),
        new OrgRightsClaim(false, List.of(entry))));
  }

  private void setSession(final AdminSessionData data) {
    when(this.session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)).thenReturn(data);
  }
}
