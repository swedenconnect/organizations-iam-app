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
import se.swedenconnect.iam.commons.types.OrganizationID;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OrgRightsClaimParser#parse(Object)} — specifically verifying that
 * {@code organization_name#*} claim keys are collected into a {@link se.swedenconnect.iam.commons.types.LocalizedString}
 * and that {@code organization_identifier} is wrapped as an {@link OrganizationID}.
 *
 * @author Martin Lindström
 */
class OrgRightsClaimParserParseTest {

  private final OrgRightsClaimParser parser = new OrgRightsClaimParser();

  @Test
  void parse_collectsAllLocalizedNames() {
    final List<Map<String, Object>> rawClaim = List.of(Map.of(
        "organization_identifier", "2021006883",
        "organization_name#sv", "Digg - Myndigheten för Digital förvaltning",
        "organization_name#en", "Digg - Myndigheten för Digital förvaltning (English)",
        "organization_name#de", "Digg - Myndigheten för Digital förvaltning (Deutsch)",
        "functions", List.of(
            Map.of("function", "walletreg", "right", "write")
        )
    ));

    final OrgRightsClaim claim = this.parser.parse(rawClaim);

    assertThat(claim.superuser()).isFalse();
    assertThat(claim.orgEntries()).hasSize(1);

    final OrgRightsClaim.OrgEntry entry = claim.orgEntries().getFirst();

    assertThat(entry.orgIdentifier()).isEqualTo(OrganizationID.of("2021006883"));
    assertThat(entry.name().get("sv")).isEqualTo("Digg - Myndigheten för Digital förvaltning");
    assertThat(entry.name().get("en")).isEqualTo("Digg - Myndigheten för Digital förvaltning (English)");
    assertThat(entry.name().get("de")).isEqualTo("Digg - Myndigheten för Digital förvaltning (Deutsch)");
  }

  /** An entry without {@code org_level_right} parses to a {@code null} org-level right. */
  @Test
  void parse_orgLevelRightAbsent() {
    final List<Map<String, Object>> rawClaim = List.of(Map.of(
        "organization_identifier", "2021006883",
        "functions", List.of(Map.of("function", "walletreg", "right", "write"))
    ));

    final OrgRightsClaim claim = this.parser.parse(rawClaim);

    assertThat(claim.orgEntries()).hasSize(1);
    assertThat(claim.orgEntries().getFirst().orgLevelRight()).isNull();
  }

  /** {@code org_level_right} is read alongside the expanded function entries. */
  @Test
  void parse_orgLevelRightPresent() {
    final List<Map<String, Object>> rawClaim = List.of(Map.of(
        "organization_identifier", "2021006883",
        "org_level_right", "admin",
        "functions", List.of(
            Map.of("function", "demo", "right", "admin"),
            Map.of("function", "walletreg", "right", "admin")
        )
    ));

    final OrgRightsClaim claim = this.parser.parse(rawClaim);

    assertThat(claim.orgEntries()).hasSize(1);

    final OrgRightsClaim.OrgEntry entry = claim.orgEntries().getFirst();
    assertThat(entry.orgLevelRight()).isEqualTo("admin");
    assertThat(entry.functions()).containsExactly(
        new OrgRightsClaim.FunctionEntry("demo", "admin"),
        new OrgRightsClaim.FunctionEntry("walletreg", "admin"));
  }

  /**
   * An org-level right on an organization with no attached functions parses to an entry with an
   * empty functions list — the entry is kept so the organization remains enumerable.
   */
  @Test
  void parse_orgLevelRightWithEmptyFunctions() {
    final List<Map<String, Object>> rawClaim = List.of(Map.of(
        "organization_identifier", "2021006883",
        "org_level_right", "admin",
        "functions", List.of()
    ));

    final OrgRightsClaim claim = this.parser.parse(rawClaim);

    assertThat(claim.orgEntries()).hasSize(1);
    assertThat(claim.orgEntries().getFirst().orgLevelRight()).isEqualTo("admin");
    assertThat(claim.orgEntries().getFirst().functions()).isEmpty();
  }

  @Test
  void parse_skipsEntryWithInvalidOrgId() {
    final List<Map<String, Object>> rawClaim = List.of(
        Map.of(
            "organization_identifier", "not-a-valid-org",
            "functions", List.of(Map.of("function", "walletreg", "right", "write"))
        ),
        Map.of(
            "organization_identifier", "2021006883",
            "functions", List.of(Map.of("function", "walletreg", "right", "read"))
        )
    );

    final OrgRightsClaim claim = this.parser.parse(rawClaim);

    assertThat(claim.orgEntries()).hasSize(1);
    assertThat(claim.orgEntries().getFirst().orgIdentifier()).isEqualTo(OrganizationID.of("2021006883"));
  }

}
