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
package se.swedenconnect.iam.admin.keycloak;

import org.junit.jupiter.api.Test;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;
import se.swedenconnect.iam.commons.types.LocalizedString;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests how an organization's legal name is resolved when its Keycloak group is read, and how a
 * name is picked for display afterwards.
 *
 * @author Martin Lindström
 */
class OrganizationLegalNameReadTest {

  private static final String ORG = "2021006883";

  /** The untagged attribute is the legal name whenever it is present. */
  @Test
  void untaggedAttribute_isTheLegalName() {
    assertThat(KeycloakAdminClient.resolveLegalName("Myndigheten för Digital förvaltning",
        display("Digg - Myndigheten för Digital förvaltning", "Digg - Authority for Digital Government"), ORG))
        .isEqualTo("Myndigheten för Digital förvaltning");
  }

  /**
   * A group created before the legal name existed carries only tagged names. The Swedish display
   * name stands in until a human enters the registered name.
   */
  @Test
  void noUntaggedAttribute_backfilledFromSwedishDisplayName() {
    assertThat(KeycloakAdminClient.resolveLegalName(
        null, display("Digg - Myndigheten för Digital förvaltning", "Digg - Authority for Digital Government"), ORG))
        .isEqualTo("Digg - Myndigheten för Digital förvaltning");
  }

  /** With no Swedish display name the English one is used. */
  @Test
  void noUntaggedAttribute_backfilledFromEnglishDisplayName() {
    assertThat(KeycloakAdminClient.resolveLegalName(
        null, display(null, "Digg - Authority for Digital Government"), ORG))
        .isEqualTo("Digg - Authority for Digital Government");
  }

  /** A group with no name of any kind is malformed; the identifier keeps things working. */
  @Test
  void noNameAtAll_fallsBackToOrgIdentifier() {
    assertThat(KeycloakAdminClient.resolveLegalName(null, null, ORG)).isEqualTo(ORG);
    assertThat(KeycloakAdminClient.resolveLegalName("  ", null, ORG)).isEqualTo(ORG);
  }

  /** An organization with no display names shows its legal name in every language. */
  @Test
  void resolveName_noDisplayNames_usesLegalName() {
    final OrganizationInfo org = orgInfo(null);

    assertThat(org.resolveName("sv")).isEqualTo("Myndigheten för Digital förvaltning");
    assertThat(org.resolveName("en")).isEqualTo("Myndigheten för Digital förvaltning");
    assertThat(org.displayName("sv")).isNull();
    assertThat(org.displayName("en")).isNull();
  }

  /** The display name for the requested language wins when it is set. */
  @Test
  void resolveName_displayNamePerLanguage() {
    final OrganizationInfo org = orgInfo(
        display("Digg - Myndigheten för Digital förvaltning", "Digg - Authority for Digital Government"));

    assertThat(org.resolveName("sv")).isEqualTo("Digg - Myndigheten för Digital förvaltning");
    assertThat(org.resolveName("en")).isEqualTo("Digg - Authority for Digital Government");
  }

  /** With only one display name set, that one is used for the other language too. */
  @Test
  void resolveName_fallsBackToTheOtherLanguage() {
    final OrganizationInfo org = orgInfo(display("Digg - Myndigheten för Digital förvaltning", null));

    assertThat(org.resolveName("en")).isEqualTo("Digg - Myndigheten för Digital förvaltning");
    assertThat(org.displayName("en")).isNull();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static LocalizedString display(final String sv, final String en) {
    final LocalizedString s = new LocalizedString();
    if (sv != null) {
      s.add("sv", sv);
    }
    if (en != null) {
      s.add("en", en);
    }
    return s;
  }

  private static OrganizationInfo orgInfo(final LocalizedString displayName) {
    return new OrganizationInfo(
        ORG, "Myndigheten för Digital förvaltning", displayName, "group-id", List.of(), null, null);
  }
}
