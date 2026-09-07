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
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.iam.commons.types.OrganizationID;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OrgRightsClaim#organizations()}.
 *
 * @author Martin Lindström
 */
class OrgRightsClaimOrganizationsTest {

  /** An organization with attached functions and an org-level right is enumerated with that right. */
  @Test
  void organizations_withFunctionsAndOrgLevelRight() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042", "admin",
            new OrgRightsClaim.FunctionEntry("demo", "admin"),
            new OrgRightsClaim.FunctionEntry("walletreg", "admin"))
    ));

    assertThat(claim.organizations()).hasSize(1);

    final OrgRightsClaim.Organization org = claim.organizations().getFirst();
    assertThat(org.orgIdentifier()).isEqualTo(OrganizationID.of("5590026042"));
    assertThat(org.name().get("en")).isEqualTo("Litsec AB");
    assertThat(org.orgLevelRight()).isEqualTo("admin");
  }

  /** An organization where all rights are function-level is enumerated with a null org-level right. */
  @Test
  void organizations_withFunctionsAndNoOrgLevelRight() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042", null, new OrgRightsClaim.FunctionEntry("demo", "write"))
    ));

    assertThat(claim.organizations()).hasSize(1);
    assertThat(claim.organizations().getFirst().orgLevelRight()).isNull();
  }

  /**
   * An organization with an org-level right but no attached functions is still enumerated. It
   * produces no authority, so enumeration is the only way for a consumer to learn about it.
   */
  @Test
  void organizations_withOrgLevelRightAndEmptyFunctions() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5561234567", "admin")
    ));

    assertThat(claim.organizations()).hasSize(1);

    final OrgRightsClaim.Organization org = claim.organizations().getFirst();
    assertThat(org.orgIdentifier()).isEqualTo(OrganizationID.of("5561234567"));
    assertThat(org.orgLevelRight()).isEqualTo("admin");
  }

  /** Every organization entry is enumerated, in claim order. */
  @Test
  void organizations_preservesClaimOrder() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042", null, new OrgRightsClaim.FunctionEntry("demo", "read")),
        orgEntry("5561234567", "admin")
    ));

    assertThat(claim.organizations())
        .extracting(o -> o.orgIdentifier().toString())
        .containsExactly("5590026042", "5561234567");
  }

  /**
   * A superuser claim carries no organization entries, so enumeration is empty — the full list must
   * be fetched from the IAM Service API instead.
   */
  @Test
  void organizations_superuser_isEmpty() {
    final OrgRightsClaim claim = new OrgRightsClaim(true, List.of());

    assertThat(claim.organizations()).isEmpty();
  }

  /** An empty claim enumerates no organizations. */
  @Test
  void organizations_emptyClaim_isEmpty() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of());

    assertThat(claim.organizations()).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static OrgRightsClaim.OrgEntry orgEntry(final String orgId, final String orgLevelRight,
      final OrgRightsClaim.FunctionEntry... functions) {
    final LocalizedString name = new LocalizedString();
    name.add("sv", "Litsec AB");
    name.add("en", "Litsec AB");
    return new OrgRightsClaim.OrgEntry(
        OrganizationID.of(orgId), "Litsec AB", name, orgLevelRight, List.of(functions));
  }
}
