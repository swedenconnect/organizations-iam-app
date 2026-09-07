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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.iam.commons.types.OrganizationID;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.FunctionInfo;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminDataBootstrapService}.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminDataBootstrapServiceTest {

  @Mock
  private KeycloakAdminClient keycloakAdminClient;

  private AdminDataBootstrapService service;

  private static final String SUBJECT = "test-user-subject";

  // ---------------------------------------------------------------------------
  // Test data builders
  // ---------------------------------------------------------------------------

  private static FunctionInfo functionInfo(final String id) {
    return new FunctionInfo(id, new LocalizedString(), null);
  }

  private static OrgRightsClaim superuserClaim() {
    return new OrgRightsClaim(true, List.of());
  }

  private static OrgRightsClaim.OrgEntry orgEntry(final String orgId,
      final OrgRightsClaim.FunctionEntry... functions) {
    return new OrgRightsClaim.OrgEntry(
        OrganizationID.of(orgId), "Litsec AB", new LocalizedString(), null, List.of(functions));
  }

  /** As {@link #orgEntry(String, OrgRightsClaim.FunctionEntry...)}, but with an org-level right set. */
  private static OrgRightsClaim.OrgEntry orgEntry(final String orgId, final String orgLevelRight,
      final OrgRightsClaim.FunctionEntry... functions) {
    return new OrgRightsClaim.OrgEntry(
        OrganizationID.of(orgId), "Litsec AB", new LocalizedString(), orgLevelRight, List.of(functions));
  }

  @BeforeEach
  void setUp() {
    service = new AdminDataBootstrapService(keycloakAdminClient);
  }

  // ---------------------------------------------------------------------------
  // Test 1: superuser, no constraint, all data returned
  // ---------------------------------------------------------------------------

  @Test
  void bootstrap_superuser_noConstraint_allData() {
    final FunctionInfo demo = functionInfo("demo");
    final FunctionInfo walletreg = functionInfo("walletreg");

    when(keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(demo, walletreg));

    final AdminSessionData result =
        service.bootstrap(superuserClaim(), SUBJECT, null, null);

    assertThat(result.functions()).containsExactlyInAnyOrder(demo, walletreg);
    assertThat(result.currentUserIsSuperuser()).isTrue();
    assertThat(result.functionConstraint()).isNull();
    assertThat(result.orgConstraint()).isNull();
  }

  // ---------------------------------------------------------------------------
  // Test 2: superuser, function constraint, only matching function returned
  // ---------------------------------------------------------------------------

  @Test
  void bootstrap_superuser_withFunctionConstraint_filteredFunctions() {
    final FunctionInfo demo = functionInfo("demo");
    final FunctionInfo walletreg = functionInfo("walletreg");

    when(keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(demo, walletreg));

    final AdminSessionData result =
        service.bootstrap(superuserClaim(), SUBJECT, "demo", null);

    assertThat(result.functions()).containsExactly(demo);
    assertThat(result.functions()).doesNotContain(walletreg);
  }

  // ---------------------------------------------------------------------------
  // Test 3: superuser, function constraint stored for API-level filtering
  // ---------------------------------------------------------------------------

  @Test
  void bootstrap_superuser_withConstraint_functionStoredForApiFiltering() {
    final FunctionInfo demo = functionInfo("demo");
    final FunctionInfo walletreg = functionInfo("walletreg");

    when(keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(demo, walletreg));

    final AdminSessionData result =
        service.bootstrap(superuserClaim(), SUBJECT, "demo", null);

    // Only the constrained function is in the session; orgs/users are loaded on-demand by API
    assertThat(result.functions()).containsExactly(demo);
    assertThat(result.functionConstraint()).isEqualTo("demo");
  }

  // ---------------------------------------------------------------------------
  // Test 4: superuser, adminOrgIdentifiers empty (superusers have full access)
  // ---------------------------------------------------------------------------

  @Test
  void bootstrap_superuser_adminOrgIdentifiers_empty() {
    when(keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of());

    final AdminSessionData result =
        service.bootstrap(superuserClaim(), SUBJECT, null, null);

    assertThat(result.adminOrgIdentifiers()).isEmpty();
    assertThat(result.currentUserIsSuperuser()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // Test 5: function constraint is stored in result
  // ---------------------------------------------------------------------------

  @Test
  void bootstrap_constraintStoredInResult() {
    final FunctionInfo demo = functionInfo("demo");

    when(keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(demo));

    final AdminSessionData result =
        service.bootstrap(superuserClaim(), SUBJECT, "demo", null);

    assertThat(result.functionConstraint()).isEqualTo("demo");
  }

  // ---------------------------------------------------------------------------
  // Test 6: null constraint is not stored in result
  // ---------------------------------------------------------------------------

  @Test
  void bootstrap_nullConstraint_notStoredInResult() {
    final FunctionInfo demo = functionInfo("demo");

    when(keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(demo));

    final AdminSessionData result =
        service.bootstrap(superuserClaim(), SUBJECT, null, null);

    assertThat(result.functionConstraint()).isNull();
  }

  // ---------------------------------------------------------------------------
  // Test 7: non-superuser, adminOrgIdentifiers derived from claim
  // ---------------------------------------------------------------------------

  @Test
  void bootstrap_regularUser_adminOrgIdentifiersDerivedFromClaim() {
    final FunctionInfo demo = functionInfo("demo");

    // Org-level admin, already expanded by the mapper onto the attached function 'demo'
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042", "admin", new OrgRightsClaim.FunctionEntry("demo", "admin"))));

    when(keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(demo));

    final AdminSessionData result =
        service.bootstrap(claim, SUBJECT, null, null);

    assertThat(result.adminOrgIdentifiers()).containsExactly("5590026042");
    assertThat(result.currentUserIsSuperuser()).isFalse();
    assertThat(result.functions()).containsExactly(demo);
  }

  // ---------------------------------------------------------------------------
  // Test 8: the function list follows the claim's function entries only — a function that is not
  // attached to the organization is never granted, even to an org-level admin
  // ---------------------------------------------------------------------------

  @Test
  void bootstrap_regularUser_unattachedFunctionNotIncluded() {
    final FunctionInfo demo = functionInfo("demo");
    final FunctionInfo walletreg = functionInfo("walletreg");

    // Org-level admin on an org where only 'demo' is attached
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042", "admin", new OrgRightsClaim.FunctionEntry("demo", "admin"))));

    when(keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(demo, walletreg));

    final AdminSessionData result =
        service.bootstrap(claim, SUBJECT, null, null);

    assertThat(result.functions()).containsExactly(demo);
  }

  // ---------------------------------------------------------------------------
  // Test 9: org-level admin on an organization with no attached functions still counts as an
  // administered organization, but yields no functions
  // ---------------------------------------------------------------------------

  @Test
  void bootstrap_regularUser_orgLevelAdminWithNoAttachedFunctions() {
    final FunctionInfo demo = functionInfo("demo");

    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042", "admin")));

    when(keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(demo));

    final AdminSessionData result =
        service.bootstrap(claim, SUBJECT, null, null);

    assertThat(result.adminOrgIdentifiers()).containsExactly("5590026042");
    assertThat(result.functions()).isEmpty();
  }
}
