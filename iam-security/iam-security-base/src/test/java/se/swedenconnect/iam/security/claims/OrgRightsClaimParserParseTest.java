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
        "organization_identifier", "5590026042",
        "organization_name#sv", "Litsec AB",
        "organization_name#en", "Litsec AB (English)",
        "organization_name#de", "Litsec AB (Deutsch)",
        "functions", List.of(
            Map.of("function", "walletreg", "right", "write")
        )
    ));

    final OrgRightsClaim claim = this.parser.parse(rawClaim);

    assertThat(claim.superuser()).isFalse();
    assertThat(claim.orgEntries()).hasSize(1);

    final OrgRightsClaim.OrgEntry entry = claim.orgEntries().getFirst();

    assertThat(entry.orgIdentifier()).isEqualTo(OrganizationID.of("5590026042"));
    assertThat(entry.name().get("sv")).isEqualTo("Litsec AB");
    assertThat(entry.name().get("en")).isEqualTo("Litsec AB (English)");
    assertThat(entry.name().get("de")).isEqualTo("Litsec AB (Deutsch)");
  }

  @Test
  void parse_skipsEntryWithInvalidOrgId() {
    final List<Map<String, Object>> rawClaim = List.of(
        Map.of(
            "organization_identifier", "not-a-valid-org",
            "functions", List.of(Map.of("function", "walletreg", "right", "write"))
        ),
        Map.of(
            "organization_identifier", "5590026042",
            "functions", List.of(Map.of("function", "walletreg", "right", "read"))
        )
    );

    final OrgRightsClaim claim = this.parser.parse(rawClaim);

    assertThat(claim.orgEntries()).hasSize(1);
    assertThat(claim.orgEntries().getFirst().orgIdentifier()).isEqualTo(OrganizationID.of("5590026042"));
  }

}
