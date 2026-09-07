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
package se.swedenconnect.iam.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import se.swedenconnect.iam.admin.controllers.dto.OrganizationPageResponse;
import se.swedenconnect.iam.admin.controllers.dto.OrganizationResponse;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;
import se.swedenconnect.iam.admin.service.cache.InMemoryOrganizationCache;
import se.swedenconnect.iam.commons.types.LocalizedString;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Server-side organization search matches the legal name as well as both display names, and the
 * response carries the legal name alongside the optional display names.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
class OrganizationSearchTest {

  private static final String WITH_DISPLAY_NAMES = "5590026042";
  private static final String LEGAL_NAME_ONLY = "5561234567";

  @Mock
  private KeycloakAdminClient keycloakAdminClient;

  private OrganizationServiceImpl service;

  @BeforeEach
  void setUp() {
    this.service = new OrganizationServiceImpl(
        this.keycloakAdminClient, new InMemoryOrganizationCache());
    when(this.keycloakAdminClient.fetchAllOrganizationGroups()).thenReturn(List.of(
        orgInfo(WITH_DISPLAY_NAMES, "Litsec Aktiebolag", "Litsec AB", "Litsec Ltd"),
        orgInfo(LEGAL_NAME_ONLY, "Exempel Aktiebolag", null, null)));
  }

  /** The legal name is searchable, including for an organization that has no display names. */
  @Test
  void search_matchesLegalName() {
    assertThat(identifiersMatching("aktiebolag"))
        .containsExactlyInAnyOrder(WITH_DISPLAY_NAMES, LEGAL_NAME_ONLY);
    assertThat(identifiersMatching("exempel")).containsExactly(LEGAL_NAME_ONLY);
  }

  /** Both display names remain searchable. */
  @Test
  void search_matchesDisplayNames() {
    assertThat(identifiersMatching("Litsec AB")).containsExactly(WITH_DISPLAY_NAMES);
    assertThat(identifiersMatching("Ltd")).containsExactly(WITH_DISPLAY_NAMES);
  }

  /** The organization number still matches, as it did before. */
  @Test
  void search_matchesOrgNumber() {
    assertThat(identifiersMatching(LEGAL_NAME_ONLY)).containsExactly(LEGAL_NAME_ONLY);
  }

  /** The response carries the legal name, and null display names where none are set. */
  @Test
  void response_carriesLegalNameAndNullDisplayNames() {
    final OrganizationPageResponse page = this.service.list(0, 50);

    final OrganizationResponse legalOnly = page.content().stream()
        .filter(o -> LEGAL_NAME_ONLY.equals(o.orgIdentifier()))
        .findFirst()
        .orElseThrow();
    assertThat(legalOnly.legalName()).isEqualTo("Exempel Aktiebolag");
    assertThat(legalOnly.nameSv()).isNull();
    assertThat(legalOnly.nameEn()).isNull();

    final OrganizationResponse withDisplay = page.content().stream()
        .filter(o -> WITH_DISPLAY_NAMES.equals(o.orgIdentifier()))
        .findFirst()
        .orElseThrow();
    assertThat(withDisplay.legalName()).isEqualTo("Litsec Aktiebolag");
    assertThat(withDisplay.nameSv()).isEqualTo("Litsec AB");
    assertThat(withDisplay.nameEn()).isEqualTo("Litsec Ltd");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private List<String> identifiersMatching(final String query) {
    return this.service.searchByName(query, 0, 50).content().stream()
        .map(OrganizationResponse::orgIdentifier)
        .toList();
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
