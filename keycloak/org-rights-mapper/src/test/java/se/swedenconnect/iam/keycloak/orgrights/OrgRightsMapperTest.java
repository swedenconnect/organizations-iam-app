/*
 * Copyright 2026 Sweden Connect
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.swedenconnect.iam.keycloak.orgrights;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.representations.IDToken;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.ATTR_ORGANIZATION_IDENTIFIER;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.ATTR_ORGANIZATION_NAME_EN;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.ATTR_ORGANIZATION_NAME_SV;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.CLAIM_FIELD_FUNCTION;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.CLAIM_FIELD_FUNCTIONS;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.CLAIM_FIELD_RIGHT;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.CLAIM_FIELD_SUPERUSER;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.CLAIM_NAME;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.FUNCTION_WILDCARD;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.GROUP_ORGS;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.REALM_ROLE_SUPERUSER;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.RIGHT_ADMIN;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.RIGHT_GROUP_ADMIN;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.RIGHT_GROUP_READ;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.RIGHT_GROUP_WRITE;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.RIGHT_READ;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.RIGHT_WRITE;

/**
 * Unit tests for {@link OrgRightsMapper}.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgRightsMapperTest {

  private OrgRightsMapper mapper;

  @Mock
  private KeycloakSession keycloakSession;

  @Mock
  private KeycloakContext keycloakContext;

  @Mock
  private RealmModel realm;

  @Mock
  private UserSessionModel userSession;

  @Mock
  private UserModel user;

  @Mock
  private ProtocolMapperModel mappingModel;

  @Mock
  private ClientSessionContext clientSessionCtx;

  @BeforeEach
  void setUp() {
    mapper = new OrgRightsMapper();
    when(keycloakSession.getContext()).thenReturn(keycloakContext);
    when(keycloakContext.getRealm()).thenReturn(realm);
    when(userSession.getUser()).thenReturn(user);
  }

  /**
   * Test 1: Superuser. User has the superuser realm role.
   * Verify the claim is [{superuser: true}] and nothing else.
   */
  @Test
  void testSuperuser() {
    final RoleModel superuserRole = mock(RoleModel.class);
    when(realm.getRole(REALM_ROLE_SUPERUSER)).thenReturn(superuserRole);
    when(user.hasRole(superuserRole)).thenReturn(true);

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> orgRights =
        (List<Map<String, Object>>) token.getOtherClaims().get(CLAIM_NAME);

    assertNotNull(orgRights);
    assertEquals(1, orgRights.size());
    assertEquals(true, orgRights.get(0).get(CLAIM_FIELD_SUPERUSER));
    assertEquals(1, orgRights.get(0).size()); // only the superuser key
  }

  /**
   * Test 2: Org-level right. User is a member of orgs/5590026042/_write.
   * Expected: one entry with functions=[{function:"*", right:"write"}].
   */
  @Test
  void testOrgLevelRight() {
    when(realm.getRole(REALM_ROLE_SUPERUSER)).thenReturn(null);

    final GroupModel orgsGroup = mockGroup("orgs-id", GROUP_ORGS, null);
    when(realm.getTopLevelGroupsStream()).thenReturn(Stream.of(orgsGroup));
    when(orgsGroup.getId()).thenReturn("orgs-id");

    final GroupModel orgGroup = mockGroup("org1-id", "5590026042", "orgs-id");
    when(orgsGroup.getSubGroupsStream()).thenReturn(Stream.of(orgGroup));
    when(orgGroup.getAttributes()).thenReturn(Map.of(
        ATTR_ORGANIZATION_IDENTIFIER, List.of("5590026042"),
        ATTR_ORGANIZATION_NAME_SV,    List.of("Litsec AB"),
        ATTR_ORGANIZATION_NAME_EN,    List.of("Litsec AB")));
    when(orgGroup.getParentId()).thenReturn("orgs-id");

    final GroupModel writeGroup = mockGroup("org-write-id", RIGHT_GROUP_WRITE, "org1-id");
    when(writeGroup.getParent()).thenReturn(orgGroup);

    when(user.getGroupsStream()).thenReturn(Stream.of(writeGroup));

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> orgRights =
        (List<Map<String, Object>>) token.getOtherClaims().get(CLAIM_NAME);

    assertNotNull(orgRights);
    assertEquals(1, orgRights.size());

    final Map<String, Object> entry = orgRights.get(0);
    assertEquals("5590026042", entry.get(ATTR_ORGANIZATION_IDENTIFIER));
    assertEquals("Litsec AB",  entry.get(ATTR_ORGANIZATION_NAME_SV));
    assertEquals("Litsec AB",  entry.get(ATTR_ORGANIZATION_NAME_EN));
    assertTrue(entry.containsKey(CLAIM_FIELD_FUNCTIONS));

    @SuppressWarnings("unchecked")
    final List<Map<String, String>> functions =
        (List<Map<String, String>>) entry.get(CLAIM_FIELD_FUNCTIONS);
    assertEquals(1, functions.size());
    assertEquals(FUNCTION_WILDCARD, functions.get(0).get(CLAIM_FIELD_FUNCTION));
    assertEquals(RIGHT_WRITE,       functions.get(0).get(CLAIM_FIELD_RIGHT));
  }

  /**
   * Test 3: Function-level right. User is a member of orgs/5590026042/walletreg/_read.
   * Expected: one entry with functions=[{function:"walletreg", right:"read"}].
   */
  @Test
  void testFunctionLevelRight() {
    when(realm.getRole(REALM_ROLE_SUPERUSER)).thenReturn(null);

    final GroupModel orgsGroup = mockGroup("orgs-id", GROUP_ORGS, null);
    when(realm.getTopLevelGroupsStream()).thenReturn(Stream.of(orgsGroup));
    when(orgsGroup.getId()).thenReturn("orgs-id");

    final GroupModel orgGroup = mockGroup("org1-id", "5590026042", "orgs-id");
    when(orgsGroup.getSubGroupsStream()).thenReturn(Stream.of(orgGroup));
    when(orgGroup.getAttributes()).thenReturn(Map.of(
        ATTR_ORGANIZATION_IDENTIFIER, List.of("5590026042"),
        ATTR_ORGANIZATION_NAME_SV,    List.of("Litsec AB"),
        ATTR_ORGANIZATION_NAME_EN,    List.of("Litsec AB")));
    when(orgGroup.getParentId()).thenReturn("orgs-id");

    final GroupModel walletregGroup = mockGroup("walletreg-id", "walletreg", "org1-id");
    when(walletregGroup.getParent()).thenReturn(orgGroup);

    final GroupModel readGroup = mockGroup("walletreg-read-id", RIGHT_GROUP_READ, "walletreg-id");
    when(readGroup.getParent()).thenReturn(walletregGroup);

    when(user.getGroupsStream()).thenReturn(Stream.of(readGroup));

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> orgRights =
        (List<Map<String, Object>>) token.getOtherClaims().get(CLAIM_NAME);

    assertNotNull(orgRights);
    assertEquals(1, orgRights.size());

    final Map<String, Object> entry = orgRights.get(0);
    assertEquals("5590026042", entry.get(ATTR_ORGANIZATION_IDENTIFIER));

    @SuppressWarnings("unchecked")
    final List<Map<String, String>> functions =
        (List<Map<String, String>>) entry.get(CLAIM_FIELD_FUNCTIONS);
    assertEquals(1, functions.size());
    assertEquals("walletreg", functions.get(0).get(CLAIM_FIELD_FUNCTION));
    assertEquals(RIGHT_READ,  functions.get(0).get(CLAIM_FIELD_RIGHT));
  }

  /**
   * Test 4: Multiple organizations. User has function-level read on org A and org-level admin on
   * org B. Verify two separate org entries are emitted, each with their own functions array.
   */
  @Test
  void testMultipleOrganizations() {
    when(realm.getRole(REALM_ROLE_SUPERUSER)).thenReturn(null);

    final GroupModel orgsGroup = mockGroup("orgs-id", GROUP_ORGS, null);
    when(realm.getTopLevelGroupsStream()).thenReturn(Stream.of(orgsGroup));
    when(orgsGroup.getId()).thenReturn("orgs-id");

    // Org A — function-level read on walletreg
    final GroupModel orgA = mockGroup("orgA-id", "1111111111", "orgs-id");
    when(orgA.getAttributes()).thenReturn(Map.of(
        ATTR_ORGANIZATION_IDENTIFIER, List.of("1111111111"),
        ATTR_ORGANIZATION_NAME_SV,    List.of("Org A"),
        ATTR_ORGANIZATION_NAME_EN,    List.of("Org A En")));
    when(orgA.getParentId()).thenReturn("orgs-id");

    final GroupModel walletregA = mockGroup("walletregA-id", "walletreg", "orgA-id");
    when(walletregA.getParent()).thenReturn(orgA);

    final GroupModel readA = mockGroup("readA-id", RIGHT_GROUP_READ, "walletregA-id");
    when(readA.getParent()).thenReturn(walletregA);

    // Org B — org-level admin
    final GroupModel orgB = mockGroup("orgB-id", "2222222222", "orgs-id");
    when(orgB.getAttributes()).thenReturn(Map.of(
        ATTR_ORGANIZATION_IDENTIFIER, List.of("2222222222"),
        ATTR_ORGANIZATION_NAME_SV,    List.of("Org B"),
        ATTR_ORGANIZATION_NAME_EN,    List.of("Org B En")));
    when(orgB.getParentId()).thenReturn("orgs-id");

    final GroupModel adminB = mockGroup("adminB-id", RIGHT_GROUP_ADMIN, "orgB-id");
    when(adminB.getParent()).thenReturn(orgB);

    // getSubGroupsStream called once per org during entry-building phase
    when(orgsGroup.getSubGroupsStream())
        .thenReturn(Stream.of(orgA, orgB))
        .thenReturn(Stream.of(orgA, orgB));

    when(user.getGroupsStream()).thenReturn(Stream.of(readA, adminB));

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> orgRights =
        (List<Map<String, Object>>) token.getOtherClaims().get(CLAIM_NAME);

    assertNotNull(orgRights);
    assertEquals(2, orgRights.size());

    // Entries are in insertion order (order groups were iterated by Mockito)
    final Map<String, Object> firstEntry  = orgRights.get(0);
    final Map<String, Object> secondEntry = orgRights.get(1);

    // Find which is org A and which is org B by identifier
    final Map<String, Object> orgAEntry = firstEntry.get(ATTR_ORGANIZATION_IDENTIFIER).equals("1111111111")
        ? firstEntry : secondEntry;
    final Map<String, Object> orgBEntry = firstEntry.get(ATTR_ORGANIZATION_IDENTIFIER).equals("2222222222")
        ? firstEntry : secondEntry;

    @SuppressWarnings("unchecked")
    final List<Map<String, String>> funcA = (List<Map<String, String>>) orgAEntry.get(CLAIM_FIELD_FUNCTIONS);
    assertEquals(1, funcA.size());
    assertEquals("walletreg", funcA.get(0).get(CLAIM_FIELD_FUNCTION));
    assertEquals(RIGHT_READ,  funcA.get(0).get(CLAIM_FIELD_RIGHT));

    @SuppressWarnings("unchecked")
    final List<Map<String, String>> funcB = (List<Map<String, String>>) orgBEntry.get(CLAIM_FIELD_FUNCTIONS);
    assertEquals(1, funcB.size());
    assertEquals(FUNCTION_WILDCARD, funcB.get(0).get(CLAIM_FIELD_FUNCTION));
    assertEquals(RIGHT_ADMIN,       funcB.get(0).get(CLAIM_FIELD_RIGHT));
  }

  /**
   * Test 5: Org-level and function-level on the same organization. User is a member of both
   * orgs/5590026042/_read and orgs/5590026042/walletreg/_write. Both must appear in a single
   * org entry's functions array.
   */
  @Test
  void testOrgAndFunctionLevelOnSameOrg() {
    when(realm.getRole(REALM_ROLE_SUPERUSER)).thenReturn(null);

    final GroupModel orgsGroup = mockGroup("orgs-id", GROUP_ORGS, null);
    when(realm.getTopLevelGroupsStream()).thenReturn(Stream.of(orgsGroup));
    when(orgsGroup.getId()).thenReturn("orgs-id");

    final GroupModel orgGroup = mockGroup("org1-id", "5590026042", "orgs-id");
    when(orgsGroup.getSubGroupsStream()).thenReturn(Stream.of(orgGroup));
    when(orgGroup.getAttributes()).thenReturn(Map.of(
        ATTR_ORGANIZATION_IDENTIFIER, List.of("5590026042"),
        ATTR_ORGANIZATION_NAME_SV,    List.of("Litsec AB"),
        ATTR_ORGANIZATION_NAME_EN,    List.of("Litsec AB")));
    when(orgGroup.getParentId()).thenReturn("orgs-id");

    // Org-level _read membership
    final GroupModel orgReadGroup = mockGroup("org-read-id", RIGHT_GROUP_READ, "org1-id");
    when(orgReadGroup.getParent()).thenReturn(orgGroup);

    // Function-level _write membership under walletreg
    final GroupModel walletregGroup = mockGroup("walletreg-id", "walletreg", "org1-id");
    when(walletregGroup.getParent()).thenReturn(orgGroup);

    final GroupModel funcWriteGroup = mockGroup("func-write-id", RIGHT_GROUP_WRITE, "walletreg-id");
    when(funcWriteGroup.getParent()).thenReturn(walletregGroup);

    when(user.getGroupsStream()).thenReturn(Stream.of(orgReadGroup, funcWriteGroup));

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> orgRights =
        (List<Map<String, Object>>) token.getOtherClaims().get(CLAIM_NAME);

    assertNotNull(orgRights);
    assertEquals(1, orgRights.size()); // single org entry

    final Map<String, Object> entry = orgRights.get(0);
    assertEquals("5590026042", entry.get(ATTR_ORGANIZATION_IDENTIFIER));

    @SuppressWarnings("unchecked")
    final List<Map<String, String>> functions =
        (List<Map<String, String>>) entry.get(CLAIM_FIELD_FUNCTIONS);
    assertEquals(2, functions.size());

    // Find the wildcard and named entries by function value
    final Map<String, String> wildcardEntry = functions.stream()
        .filter(f -> FUNCTION_WILDCARD.equals(f.get(CLAIM_FIELD_FUNCTION)))
        .findFirst()
        .orElseThrow();
    final Map<String, String> walletregEntry = functions.stream()
        .filter(f -> "walletreg".equals(f.get(CLAIM_FIELD_FUNCTION)))
        .findFirst()
        .orElseThrow();

    assertEquals(RIGHT_READ,  wildcardEntry.get(CLAIM_FIELD_RIGHT));
    assertEquals(RIGHT_WRITE, walletregEntry.get(CLAIM_FIELD_RIGHT));
  }

  /**
   * Test 6: No relevant groups. User is not a member of any group under orgs.
   * Verify the claim is an empty list.
   */
  @Test
  void testNoRelevantGroups() {
    when(realm.getRole(REALM_ROLE_SUPERUSER)).thenReturn(null);

    final GroupModel orgsGroup = mockGroup("orgs-id", GROUP_ORGS, null);
    when(realm.getTopLevelGroupsStream()).thenReturn(Stream.of(orgsGroup));

    final GroupModel unrelatedGroup = mockGroup("other-id", "some-other-group", null);
    when(unrelatedGroup.getParent()).thenReturn(null);
    when(user.getGroupsStream()).thenReturn(Stream.of(unrelatedGroup));

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> orgRights =
        (List<Map<String, Object>>) token.getOtherClaims().get(CLAIM_NAME);

    assertNotNull(orgRights);
    assertTrue(orgRights.isEmpty());
  }

  /**
   * Test 7: Missing org attributes. Org group has no organization_name#sv attribute.
   * Verify the mapper does not throw and uses empty string as fallback.
   */
  @Test
  void testMissingOrgAttributes() {
    when(realm.getRole(REALM_ROLE_SUPERUSER)).thenReturn(null);

    final GroupModel orgsGroup = mockGroup("orgs-id", GROUP_ORGS, null);
    when(realm.getTopLevelGroupsStream()).thenReturn(Stream.of(orgsGroup));
    when(orgsGroup.getId()).thenReturn("orgs-id");

    final GroupModel orgGroup = mockGroup("org1-id", "5590026042", "orgs-id");
    when(orgsGroup.getSubGroupsStream()).thenReturn(Stream.of(orgGroup));
    // ATTR_ORGANIZATION_NAME_SV is intentionally absent
    when(orgGroup.getAttributes()).thenReturn(Map.of(
        ATTR_ORGANIZATION_IDENTIFIER, List.of("5590026042"),
        ATTR_ORGANIZATION_NAME_EN,    List.of("Litsec AB")));
    when(orgGroup.getParentId()).thenReturn("orgs-id");

    final GroupModel writeGroup = mockGroup("org-write-id", RIGHT_GROUP_WRITE, "org1-id");
    when(writeGroup.getParent()).thenReturn(orgGroup);

    when(user.getGroupsStream()).thenReturn(Stream.of(writeGroup));

    final IDToken token = new IDToken();
    // Must not throw
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> orgRights =
        (List<Map<String, Object>>) token.getOtherClaims().get(CLAIM_NAME);

    assertNotNull(orgRights);
    assertEquals(1, orgRights.size());
    assertEquals("",          orgRights.get(0).get(ATTR_ORGANIZATION_NAME_SV));
    assertEquals("Litsec AB", orgRights.get(0).get(ATTR_ORGANIZATION_NAME_EN));
  }

  // ---- helpers ----

  private GroupModel mockGroup(final String id, final String name, final String parentId) {
    final GroupModel g = mock(GroupModel.class);
    when(g.getId()).thenReturn(id);
    when(g.getName()).thenReturn(name);
    when(g.getParentId()).thenReturn(parentId);
    return g;
  }
}
