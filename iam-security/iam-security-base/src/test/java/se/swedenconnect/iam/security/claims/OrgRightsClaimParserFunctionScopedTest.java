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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.iam.commons.types.OrganizationID;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OrgRightsClaimParser#buildFunctionScopedAuthorities(OrgRightsClaim, String)}.
 *
 * @author Martin Lindström
 */
class OrgRightsClaimParserFunctionScopedTest {

  private final OrgRightsClaimParser parser = new OrgRightsClaimParser();

  private static OrgRightsClaim.OrgEntry orgEntry(final String orgId, final OrgRightsClaim.FunctionEntry... functions) {
    return new OrgRightsClaim.OrgEntry(OrganizationID.of(orgId), new LocalizedString(), null, List.of(functions));
  }

  /** As {@link #orgEntry(String, OrgRightsClaim.FunctionEntry...)}, but with an org-level right set. */
  private static OrgRightsClaim.OrgEntry orgEntry(final String orgId, final String orgLevelRight,
      final OrgRightsClaim.FunctionEntry... functions) {
    return new OrgRightsClaim.OrgEntry(
        OrganizationID.of(orgId), new LocalizedString(), orgLevelRight, List.of(functions));
  }

  /** Superuser always receives ROLE_SUPERUSER regardless of function. */
  @Test
  void superuser_receivesRoleSuperuser() {
    final OrgRightsClaim claim = new OrgRightsClaim(true, List.of());
    final List<GrantedAuthority> authorities = this.parser.buildFunctionScopedAuthorities(claim, "walletreg");
    assertThat(authorities).containsExactly(new SimpleGrantedAuthority("ROLE_SUPERUSER"));
  }

  /**
   * An org-level right the mapper expanded onto the configured function produces that right as a
   * FunctionScopedAuthority.
   */
  @Test
  void orgLevelRightExpandedOntoFunction_producesCorrectAuthority() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042", "read", new OrgRightsClaim.FunctionEntry("walletreg", "read"))
    ));
    final List<GrantedAuthority> authorities = this.parser.buildFunctionScopedAuthorities(claim, "walletreg");
    assertThat(authorities).containsExactly(
        FunctionScopedAuthority.of(OrganizationID.of("5590026042"), OrganizationRight.READ));
  }

  /**
   * An org-level right grants nothing for a function that is not attached to the organization — the
   * mapper expanded it only onto {@code demo}, so a walletreg-scoped application sees no right.
   * {@code orgLevelRight} must never be treated as a grant in its own right.
   */
  @Test
  void orgLevelRightOnUnattachedFunction_producesNoAuthority() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042", "admin", new OrgRightsClaim.FunctionEntry("demo", "admin"))
    ));
    final List<GrantedAuthority> authorities = this.parser.buildFunctionScopedAuthorities(claim, "walletreg");
    assertThat(authorities).isEmpty();
  }

  /** An org-level right on an organization with no attached functions grants nothing. */
  @Test
  void orgLevelRightWithNoAttachedFunctions_producesNoAuthority() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042", "admin")
    ));
    final List<GrantedAuthority> authorities = this.parser.buildFunctionScopedAuthorities(claim, "walletreg");
    assertThat(authorities).isEmpty();
  }

  /** User with only an exact function match right gets that right as a FunctionScopedAuthority. */
  @Test
  void exactFunctionOnly_producesCorrectAuthority() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042", new OrgRightsClaim.FunctionEntry("walletreg", "write"))
    ));
    final List<GrantedAuthority> authorities = this.parser.buildFunctionScopedAuthorities(claim, "walletreg");
    assertThat(authorities).containsExactly(
        FunctionScopedAuthority.of(OrganizationID.of("5590026042"), OrganizationRight.WRITE));
  }

  /** When several entries name the same function, the highest right wins. */
  @Test
  void duplicateFunctionEntries_highestRightWins() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042",
            new OrgRightsClaim.FunctionEntry("walletreg", "read"),
            new OrgRightsClaim.FunctionEntry("walletreg", "write"))
    ));
    final List<GrantedAuthority> authorities = this.parser.buildFunctionScopedAuthorities(claim, "walletreg");
    assertThat(authorities).containsExactly(
        FunctionScopedAuthority.of(OrganizationID.of("5590026042"), OrganizationRight.WRITE));
  }

  /** User with no entries matching the configured function receives an empty authority list. */
  @Test
  void noMatchingEntries_producesEmptyList() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042", new OrgRightsClaim.FunctionEntry("sweden-connect", "write"))
    ));
    final List<GrantedAuthority> authorities = this.parser.buildFunctionScopedAuthorities(claim, "walletreg");
    assertThat(authorities).isEmpty();
  }

  /** Multiple orgs each produce their own FunctionScopedAuthority with the correct highest right. */
  @Test
  void multipleOrgs_eachGetHighestRight() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042", "read",
            new OrgRightsClaim.FunctionEntry("demo", "read"),
            new OrgRightsClaim.FunctionEntry("walletreg", "admin")),
        orgEntry("5561234567",
            new OrgRightsClaim.FunctionEntry("walletreg", "write"))
    ));
    final List<GrantedAuthority> authorities = this.parser.buildFunctionScopedAuthorities(claim, "walletreg");
    assertThat(authorities).containsExactlyInAnyOrder(
        FunctionScopedAuthority.of(OrganizationID.of("5590026042"), OrganizationRight.ADMIN),
        FunctionScopedAuthority.of(OrganizationID.of("5561234567"), OrganizationRight.WRITE)
    );
  }

}
