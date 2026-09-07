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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.iam.security.claims.OrgRightsClaimParser;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The {@code GET /iam-api/v1/organizations} response carries {@code legal_name} for every
 * organization. {@code name#sv} and {@code name#en} are the optional display names, except that
 * {@code name#sv} is filled with the legal name when no Swedish display name is set, so that
 * callers written before {@code legal_name} existed keep working.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
class IamServiceOrganizationsResponseTest {

  private static final String WITH_DISPLAY_NAMES = "5590026042";
  private static final String LEGAL_NAME_ONLY = "5561234567";

  @Mock
  private KeycloakAdminClient keycloakAdminClient;

  private IamServiceOrganizationController controller;

  @BeforeEach
  void setUp() {
    this.controller = new IamServiceOrganizationController(
        this.keycloakAdminClient, new OrgRightsClaimParser());
  }

  /** Display names are returned as they are when both are set, alongside the legal name. */
  @Test
  void withDisplayNames_returnsBothAndLegalName() {
    when(this.keycloakAdminClient.fetchAllOrganizationGroups()).thenReturn(List.of(
        orgInfo(WITH_DISPLAY_NAMES, "Litsec Aktiebolag", "Litsec AB", "Litsec Ltd")));

    final var entry = listOrganizations().get(WITH_DISPLAY_NAMES);

    assertThat(entry.legalName()).isEqualTo("Litsec Aktiebolag");
    assertThat(entry.nameSv()).isEqualTo("Litsec AB");
    assertThat(entry.nameEn()).isEqualTo("Litsec Ltd");
  }

  /** Without a Swedish display name the legal name is substituted into {@code name#sv}. */
  @Test
  void withoutSwedishDisplayName_legalNameSubstitutedIntoNameSv() {
    when(this.keycloakAdminClient.fetchAllOrganizationGroups()).thenReturn(List.of(
        orgInfo(LEGAL_NAME_ONLY, "Exempel Aktiebolag", null, null)));

    final var entry = listOrganizations().get(LEGAL_NAME_ONLY);

    assertThat(entry.legalName()).isEqualTo("Exempel Aktiebolag");
    assertThat(entry.nameSv()).isEqualTo("Exempel Aktiebolag");
    assertThat(entry.nameEn()).isNull();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private Map<String, IamServiceOrganizationController.OrganizationEntry> listOrganizations() {
    final ResponseEntity<Map<String, IamServiceOrganizationController.OrganizationEntry>> response =
        this.controller.listOrganizations();
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    return response.getBody();
  }

  private static OrganizationInfo orgInfo(
      final String orgId, final String legalName, final String nameSv, final String nameEn) {
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
    return new OrganizationInfo(orgId, legalName, displayName, orgId + "-group", List.of(), null, null);
  }
}
