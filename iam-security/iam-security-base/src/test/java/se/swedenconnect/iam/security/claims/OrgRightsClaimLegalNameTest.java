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
package se.swedenconnect.iam.security.claims;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests how {@link OrgRightsClaimParser} reads the organization's legal name: the
 * {@code organization_legal_name} member is authoritative, the untagged {@code organization_name}
 * member is a backwards-compatible duplicate that makes a language lookup resolve, and a claim from
 * an older mapper that carries neither still yields a name.
 *
 * @author Martin Lindström
 */
class OrgRightsClaimLegalNameTest {

  private static final String ORG = "5590026042";

  private final OrgRightsClaimParser parser = new OrgRightsClaimParser();

  /** An organization with no display names: the legal name is what a lookup in either language finds. */
  @Test
  void noDisplayNames_legalNameIsUsedForEveryLanguage() {
    final Map<String, Object> entry = entry();
    entry.put("organization_legal_name", "Litsec Aktiebolag");
    entry.put("organization_name", "Litsec Aktiebolag");

    final OrgRightsClaim.OrgEntry parsed = parseSingle(entry);

    assertThat(parsed.legalName()).isEqualTo("Litsec Aktiebolag");
    assertThat(parsed.name().get("sv")).isEqualTo("Litsec Aktiebolag");
    assertThat(parsed.name().get("en")).isEqualTo("Litsec Aktiebolag");
    assertThat(parsed.name().asMap()).doesNotContainKeys("sv", "en");
  }

  /** With display names set, a language lookup finds those while the legal name stays separate. */
  @Test
  void displayNames_areReadPerLanguage_legalNameUnaffected() {
    final Map<String, Object> entry = entry();
    entry.put("organization_legal_name", "Litsec Aktiebolag");
    entry.put("organization_name", "Litsec Aktiebolag");
    entry.put("organization_name#sv", "Litsec AB");
    entry.put("organization_name#en", "Litsec Ltd");

    final OrgRightsClaim.OrgEntry parsed = parseSingle(entry);

    assertThat(parsed.legalName()).isEqualTo("Litsec Aktiebolag");
    assertThat(parsed.name().get("sv")).isEqualTo("Litsec AB");
    assertThat(parsed.name().get("en")).isEqualTo("Litsec Ltd");
  }

  /** A claim from an older mapper carries only tagged names; the legal name is derived from them. */
  @Test
  void noLegalNameMember_derivedFromDisplayNames() {
    final Map<String, Object> entry = entry();
    entry.put("organization_name#sv", "Litsec AB");
    entry.put("organization_name#en", "Litsec Ltd");

    assertThat(parseSingle(entry).legalName()).isEqualTo("Litsec AB");
  }

  /** With no name of any kind the organization identifier is used, so the value is never absent. */
  @Test
  void noNamesAtAll_fallsBackToOrgIdentifier() {
    assertThat(parseSingle(entry()).legalName()).isEqualTo(ORG);
  }

  /** {@link OrgRightsClaim#organizations()} carries the legal name through. */
  @Test
  void organizations_carryTheLegalName() {
    final Map<String, Object> entry = entry();
    entry.put("organization_legal_name", "Litsec Aktiebolag");
    entry.put("organization_name", "Litsec Aktiebolag");

    final OrgRightsClaim claim = this.parser.parse(List.of(entry));

    assertThat(claim.organizations()).singleElement()
        .extracting(OrgRightsClaim.Organization::legalName)
        .isEqualTo("Litsec Aktiebolag");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Map<String, Object> entry() {
    final Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("organization_identifier", ORG);
    entry.put("functions", List.of(Map.of("function", "demo", "right", "admin")));
    return entry;
  }

  private OrgRightsClaim.OrgEntry parseSingle(final Map<String, Object> entry) {
    final OrgRightsClaim claim = this.parser.parse(List.of(entry));
    assertThat(claim.orgEntries()).hasSize(1);
    return claim.orgEntries().getFirst();
  }
}
