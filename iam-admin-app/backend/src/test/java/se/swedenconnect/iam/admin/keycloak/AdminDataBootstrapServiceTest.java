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
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;
import se.swedenconnect.iam.admin.keycloak.model.UserInfo;
import se.swedenconnect.iam.admin.keycloak.model.UserRight;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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

  private static OrganizationInfo orgInfo(final String orgIdentifier, final String groupId,
      final List<String> attachedFunctions) {
    return new OrganizationInfo(orgIdentifier, new LocalizedString(), groupId,
        attachedFunctions, null, null);
  }

  private static UserInfo userInfo(final String userId) {
    return new UserInfo(userId, null, null, null, null, null, null, false, List.of());
  }

  private static OrgRightsClaim superuserClaim() {
    return new OrgRightsClaim(true, List.of());
  }

  private static OrgRightsClaim.OrgEntry orgEntry(final String orgId,
      final OrgRightsClaim.FunctionEntry... functions) {
    return new OrgRightsClaim.OrgEntry(OrganizationID.of(orgId), new LocalizedString(), List.of(functions));
  }

  @BeforeEach
  void setUp() {
    final ObjectMapper objectMapper = new ObjectMapper();
    service = new AdminDataBootstrapService(keycloakAdminClient, objectMapper);

    // Default: no children or members for any group
    when(keycloakAdminClient.fetchGroupChildren(anyString())).thenReturn(List.of());
    when(keycloakAdminClient.fetchGroupMembers(anyString())).thenReturn(List.of());
  }

  // ---------------------------------------------------------------------------
  // Test 1: superuser, no constraint, all data returned
  // ---------------------------------------------------------------------------

  @Test
  void bootstrap_superuser_noConstraint_allData() {
    final FunctionInfo demo = functionInfo("demo");
    final FunctionInfo walletreg = functionInfo("walletreg");
    final OrganizationInfo org1 = orgInfo("5590026042", "group-1", List.of("demo", "walletreg"));
    final OrganizationInfo org2 = orgInfo("5561234567", "group-2", List.of("demo"));
    final UserInfo user1 = userInfo("user-1");
    final UserInfo user2 = userInfo("user-2");

    when(keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(demo, walletreg));
    when(keycloakAdminClient.fetchAllOrganizationGroups()).thenReturn(List.of(org1, org2));
    when(keycloakAdminClient.fetchAllUsers()).thenReturn(List.of(user1, user2));

    final AdminSessionData result =
        service.bootstrap(superuserClaim(), SUBJECT, null, null);

    assertThat(result.functions()).containsExactlyInAnyOrder(demo, walletreg);
    assertThat(result.organizations()).containsExactlyInAnyOrder(org1, org2);
    assertThat(result.users()).hasSize(2);
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
    final OrganizationInfo org1 = orgInfo("5590026042", "group-1", List.of("demo", "walletreg"));
    final UserInfo user1 = userInfo("user-1");

    when(keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(demo, walletreg));
    when(keycloakAdminClient.fetchAllOrganizationGroups()).thenReturn(List.of(org1));
    when(keycloakAdminClient.fetchAllUsers()).thenReturn(List.of(user1));

    final AdminSessionData result =
        service.bootstrap(superuserClaim(), SUBJECT, "demo", null);

    assertThat(result.functions()).containsExactly(demo);
    assertThat(result.functions()).doesNotContain(walletreg);
  }

  // ---------------------------------------------------------------------------
  // Test 3: superuser, function constraint filters attachedFunctions on orgs
  // ---------------------------------------------------------------------------

  @Test
  void bootstrap_superuser_withConstraint_filteredAttachedFunctions() {
    final FunctionInfo demo = functionInfo("demo");
    final FunctionInfo walletreg = functionInfo("walletreg");
    final OrganizationInfo org1 = orgInfo("5590026042", "group-1", List.of("demo", "walletreg"));

    when(keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(demo, walletreg));
    when(keycloakAdminClient.fetchAllOrganizationGroups()).thenReturn(List.of(org1));
    when(keycloakAdminClient.fetchAllUsers()).thenReturn(List.of());

    final AdminSessionData result =
        service.bootstrap(superuserClaim(), SUBJECT, "demo", null);

    assertThat(result.organizations()).hasSize(1);
    assertThat(result.organizations().get(0).attachedFunctions()).containsExactly("demo");
    assertThat(result.organizations().get(0).attachedFunctions()).doesNotContain("walletreg");
  }

  // ---------------------------------------------------------------------------
  // Test 4: superuser, function constraint filters user rights
  // ---------------------------------------------------------------------------

  @Test
  void bootstrap_superuser_withConstraint_filteredUserRights() {
    final FunctionInfo demo = functionInfo("demo");
    final FunctionInfo walletreg = functionInfo("walletreg");
    final OrganizationInfo org1 = orgInfo("5590026042", "group-1", List.of("demo", "walletreg"));

    // User has rights for both demo and walletreg
    final UserRight demoRight = new UserRight("5590026042", "demo", "write");
    final UserRight walletregRight = new UserRight("5590026042", "walletreg", "read");
    final UserInfo user1 = new UserInfo("user-1", null, null, null, null, null, null, false,
        List.of(demoRight, walletregRight));

    when(keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(demo, walletreg));
    when(keycloakAdminClient.fetchAllOrganizationGroups()).thenReturn(List.of(org1));
    when(keycloakAdminClient.fetchAllUsers()).thenReturn(List.of(user1));

    // The rights are fetched from group membership, not from the user object directly.
    // The bootstrap service fetches group children and members to build the rights map.
    // With no group children mocked, the user in the result has empty rights from group lookups.
    // We need to mock the group structure to return the user with rights.
    // Since this is complex, we mock the fetchGroupChildren to return a right group,
    // and fetchGroupMembers to return the user.

    // Org group-1 has a "demo" function sub-group with id "demo-group"
    final Map<String, Object> demoGroup = Map.of("id", "demo-group", "name", "demo");
    final Map<String, Object> walletregGroup = Map.of("id", "walletreg-group", "name", "walletreg");
    when(keycloakAdminClient.fetchGroupChildren("group-1"))
        .thenReturn(List.of(demoGroup, walletregGroup));

    // demo function sub-group has a _write right group
    final Map<String, Object> demoWriteGroup = Map.of("id", "demo-write-id", "name", "_write");
    when(keycloakAdminClient.fetchGroupChildren("demo-group"))
        .thenReturn(List.of(demoWriteGroup));

    // walletreg function sub-group has a _read right group
    final Map<String, Object> walletregReadGroup = Map.of("id", "walletreg-read-id", "name", "_read");
    when(keycloakAdminClient.fetchGroupChildren("walletreg-group"))
        .thenReturn(List.of(walletregReadGroup));

    // Members of the right groups
    final UserInfo userProfile = new UserInfo("user-1", "u1", null, null, null, null, null, false, List.of());
    when(keycloakAdminClient.fetchGroupMembers("demo-write-id")).thenReturn(List.of(userProfile));
    when(keycloakAdminClient.fetchGroupMembers("walletreg-read-id")).thenReturn(List.of(userProfile));

    final AdminSessionData result =
        service.bootstrap(superuserClaim(), SUBJECT, "demo", null);

    assertThat(result.users()).hasSize(1);
    final UserInfo resultUser = result.users().get(0);
    // Only demo rights should remain after function constraint filtering
    assertThat(resultUser.rights()).allMatch(r -> r.functionId() == null || "demo".equals(r.functionId()));
    assertThat(resultUser.rights()).noneMatch(r -> "walletreg".equals(r.functionId()));
  }

  // ---------------------------------------------------------------------------
  // Test 5: function constraint is stored in result
  // ---------------------------------------------------------------------------

  @Test
  void bootstrap_constraintStoredInResult() {
    final FunctionInfo demo = functionInfo("demo");
    final OrganizationInfo org1 = orgInfo("5590026042", "group-1", List.of("demo"));

    when(keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(demo));
    when(keycloakAdminClient.fetchAllOrganizationGroups()).thenReturn(List.of(org1));
    when(keycloakAdminClient.fetchAllUsers()).thenReturn(List.of());

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
    final OrganizationInfo org1 = orgInfo("5590026042", "group-1", List.of("demo"));

    when(keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(demo));
    when(keycloakAdminClient.fetchAllOrganizationGroups()).thenReturn(List.of(org1));
    when(keycloakAdminClient.fetchAllUsers()).thenReturn(List.of());

    final AdminSessionData result =
        service.bootstrap(superuserClaim(), SUBJECT, null, null);

    assertThat(result.functionConstraint()).isNull();
  }
}
