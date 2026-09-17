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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.ATTR_ORGANIZATION_IDENTIFIER;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.ATTR_ORGANIZATION_NAME;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.ATTR_ORGANIZATION_NAME_EN;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.ATTR_ORGANIZATION_NAME_SV;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.CLAIM_FIELD_FUNCTION;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.CLAIM_FIELD_FUNCTIONS;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.CLAIM_FIELD_ORGANIZATION_LEGAL_NAME;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.CLAIM_FIELD_ORG_LEVEL_RIGHT;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.CLAIM_FIELD_RIGHT;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.CLAIM_FIELD_SUPERUSER;
import static se.swedenconnect.iam.keycloak.orgrights.OrgRightsMapper.CLAIM_NAME;
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
   * Test 2: Org-level right. User is a member of orgs/2021006883/_write, and the organization has
   * the functions demo and walletreg attached.
   * Expected: org_level_right=write, and the right expanded onto both attached functions.
   */
  @Test
  void testOrgLevelRightExpandsOntoAttachedFunctions() {
    when(realm.getRole(REALM_ROLE_SUPERUSER)).thenReturn(null);

    final GroupModel orgsGroup = mockGroup("orgs-id", GROUP_ORGS, null);
    when(realm.getTopLevelGroupsStream()).thenReturn(Stream.of(orgsGroup));
    when(orgsGroup.getId()).thenReturn("orgs-id");

    final GroupModel orgGroup = mockGroup("org1-id", "2021006883", "orgs-id");
    when(orgsGroup.getSubGroupsStream()).thenReturn(Stream.of(orgGroup));
    when(orgGroup.getAttributes()).thenReturn(Map.of(
        ATTR_ORGANIZATION_IDENTIFIER, List.of("2021006883"),
        ATTR_ORGANIZATION_NAME_SV,    List.of("Digg - Myndigheten för Digital förvaltning"),
        ATTR_ORGANIZATION_NAME_EN,    List.of("Digg - Myndigheten för Digital förvaltning")));
    when(orgGroup.getParentId()).thenReturn("orgs-id");
    mockOrgChildren(orgGroup, "demo", "walletreg",
        RIGHT_GROUP_ADMIN, RIGHT_GROUP_WRITE, RIGHT_GROUP_READ);

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
    assertEquals("2021006883",                                 entry.get(ATTR_ORGANIZATION_IDENTIFIER));
    assertEquals("Digg - Myndigheten för Digital förvaltning", entry.get(ATTR_ORGANIZATION_NAME_SV));
    assertEquals("Digg - Myndigheten för Digital förvaltning", entry.get(ATTR_ORGANIZATION_NAME_EN));
    assertEquals(RIGHT_WRITE,                                  entry.get(CLAIM_FIELD_ORG_LEVEL_RIGHT));
    assertTrue(entry.containsKey(CLAIM_FIELD_FUNCTIONS));

    assertEquals(Map.of("demo", RIGHT_WRITE, "walletreg", RIGHT_WRITE), functionRights(entry));
  }

  /**
   * Test 2b: The attachment set is identified by excluding the three reserved right-group names,
   * not by a leading underscore — a function identifier such as {@code _foo} is legal per the admin
   * application's {@code [a-z0-9_-]+} rule and must still receive the expanded right.
   */
  @Test
  void testOrgLevelRightExpandsOntoUnderscoreFunctionId() {
    when(realm.getRole(REALM_ROLE_SUPERUSER)).thenReturn(null);

    final GroupModel orgsGroup = mockGroup("orgs-id", GROUP_ORGS, null);
    when(realm.getTopLevelGroupsStream()).thenReturn(Stream.of(orgsGroup));
    when(orgsGroup.getId()).thenReturn("orgs-id");

    final GroupModel orgGroup = mockGroup("org1-id", "2021006883", "orgs-id");
    when(orgsGroup.getSubGroupsStream()).thenReturn(Stream.of(orgGroup));
    when(orgGroup.getAttributes()).thenReturn(Map.of(
        ATTR_ORGANIZATION_IDENTIFIER, List.of("2021006883"),
        ATTR_ORGANIZATION_NAME_SV,    List.of("Digg - Myndigheten för Digital förvaltning"),
        ATTR_ORGANIZATION_NAME_EN,    List.of("Digg - Myndigheten för Digital förvaltning")));
    when(orgGroup.getParentId()).thenReturn("orgs-id");
    mockOrgChildren(orgGroup, "_foo", RIGHT_GROUP_ADMIN, RIGHT_GROUP_WRITE, RIGHT_GROUP_READ);

    final GroupModel adminGroup = mockGroup("org-admin-id", RIGHT_GROUP_ADMIN, "org1-id");
    when(adminGroup.getParent()).thenReturn(orgGroup);

    when(user.getGroupsStream()).thenReturn(Stream.of(adminGroup));

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> orgRights =
        (List<Map<String, Object>>) token.getOtherClaims().get(CLAIM_NAME);

    assertNotNull(orgRights);
    assertEquals(Map.of("_foo", RIGHT_ADMIN), functionRights(orgRights.get(0)));
  }

  /**
   * Test 2c: Org-level right on an organization with no functions attached.
   * Expected: the entry is still emitted, carrying org_level_right and an empty functions array.
   */
  @Test
  void testOrgLevelRightWithNoAttachedFunctions() {
    when(realm.getRole(REALM_ROLE_SUPERUSER)).thenReturn(null);

    final GroupModel orgsGroup = mockGroup("orgs-id", GROUP_ORGS, null);
    when(realm.getTopLevelGroupsStream()).thenReturn(Stream.of(orgsGroup));
    when(orgsGroup.getId()).thenReturn("orgs-id");

    final GroupModel orgGroup = mockGroup("org1-id", "5561234567", "orgs-id");
    when(orgsGroup.getSubGroupsStream()).thenReturn(Stream.of(orgGroup));
    when(orgGroup.getAttributes()).thenReturn(Map.of(
        ATTR_ORGANIZATION_IDENTIFIER, List.of("5561234567"),
        ATTR_ORGANIZATION_NAME_SV,    List.of("Exempel AB"),
        ATTR_ORGANIZATION_NAME_EN,    List.of("Example Corp")));
    when(orgGroup.getParentId()).thenReturn("orgs-id");
    // Only the right groups exist — no function has been attached
    mockOrgChildren(orgGroup, RIGHT_GROUP_ADMIN, RIGHT_GROUP_WRITE, RIGHT_GROUP_READ);

    final GroupModel adminGroup = mockGroup("org-admin-id", RIGHT_GROUP_ADMIN, "org1-id");
    when(adminGroup.getParent()).thenReturn(orgGroup);

    when(user.getGroupsStream()).thenReturn(Stream.of(adminGroup));

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> orgRights =
        (List<Map<String, Object>>) token.getOtherClaims().get(CLAIM_NAME);

    assertNotNull(orgRights);
    assertEquals(1, orgRights.size());

    final Map<String, Object> entry = orgRights.get(0);
    assertEquals("5561234567", entry.get(ATTR_ORGANIZATION_IDENTIFIER));
    assertEquals("Example Corp", entry.get(ATTR_ORGANIZATION_NAME_EN));
    assertEquals(RIGHT_ADMIN, entry.get(CLAIM_FIELD_ORG_LEVEL_RIGHT));

    @SuppressWarnings("unchecked")
    final List<Map<String, String>> functions =
        (List<Map<String, String>>) entry.get(CLAIM_FIELD_FUNCTIONS);
    assertNotNull(functions);
    assertTrue(functions.isEmpty());
  }

  /**
   * Test 3: Function-level right. User is a member of orgs/2021006883/walletreg/_read.
   * Expected: one entry with functions=[{function:"walletreg", right:"read"}] and no
   * org_level_right field.
   */
  @Test
  void testFunctionLevelRight() {
    when(realm.getRole(REALM_ROLE_SUPERUSER)).thenReturn(null);

    final GroupModel orgsGroup = mockGroup("orgs-id", GROUP_ORGS, null);
    when(realm.getTopLevelGroupsStream()).thenReturn(Stream.of(orgsGroup));
    when(orgsGroup.getId()).thenReturn("orgs-id");

    final GroupModel orgGroup = mockGroup("org1-id", "2021006883", "orgs-id");
    when(orgsGroup.getSubGroupsStream()).thenReturn(Stream.of(orgGroup));
    when(orgGroup.getAttributes()).thenReturn(Map.of(
        ATTR_ORGANIZATION_IDENTIFIER, List.of("2021006883"),
        ATTR_ORGANIZATION_NAME_SV,    List.of("Digg - Myndigheten för Digital förvaltning"),
        ATTR_ORGANIZATION_NAME_EN,    List.of("Digg - Myndigheten för Digital förvaltning")));
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
    assertEquals("2021006883", entry.get(ATTR_ORGANIZATION_IDENTIFIER));
    assertFalse(entry.containsKey(CLAIM_FIELD_ORG_LEVEL_RIGHT));

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
    mockOrgChildren(orgB, "demo", RIGHT_GROUP_ADMIN, RIGHT_GROUP_WRITE, RIGHT_GROUP_READ);

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

    assertFalse(orgAEntry.containsKey(CLAIM_FIELD_ORG_LEVEL_RIGHT));
    assertEquals(Map.of("walletreg", RIGHT_READ), functionRights(orgAEntry));

    assertEquals(RIGHT_ADMIN, orgBEntry.get(CLAIM_FIELD_ORG_LEVEL_RIGHT));
    assertEquals(Map.of("demo", RIGHT_ADMIN), functionRights(orgBEntry));
  }

  /**
   * Test 5: Org-level and function-level on the same organization. User is a member of both
   * orgs/2021006883/_read and orgs/2021006883/walletreg/_write, with demo and walletreg attached.
   * The org-level read is expanded onto both functions, and the explicit write on walletreg wins
   * over it.
   */
  @Test
  void testOrgAndFunctionLevelOnSameOrg() {
    when(realm.getRole(REALM_ROLE_SUPERUSER)).thenReturn(null);

    final GroupModel orgsGroup = mockGroup("orgs-id", GROUP_ORGS, null);
    when(realm.getTopLevelGroupsStream()).thenReturn(Stream.of(orgsGroup));
    when(orgsGroup.getId()).thenReturn("orgs-id");

    final GroupModel orgGroup = mockGroup("org1-id", "2021006883", "orgs-id");
    when(orgsGroup.getSubGroupsStream()).thenReturn(Stream.of(orgGroup));
    when(orgGroup.getAttributes()).thenReturn(Map.of(
        ATTR_ORGANIZATION_IDENTIFIER, List.of("2021006883"),
        ATTR_ORGANIZATION_NAME_SV,    List.of("Digg - Myndigheten för Digital förvaltning"),
        ATTR_ORGANIZATION_NAME_EN,    List.of("Digg - Myndigheten för Digital förvaltning")));
    when(orgGroup.getParentId()).thenReturn("orgs-id");
    mockOrgChildren(orgGroup, "demo", "walletreg",
        RIGHT_GROUP_ADMIN, RIGHT_GROUP_WRITE, RIGHT_GROUP_READ);

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
    assertEquals("2021006883", entry.get(ATTR_ORGANIZATION_IDENTIFIER));
    assertEquals(RIGHT_READ, entry.get(CLAIM_FIELD_ORG_LEVEL_RIGHT));

    // demo gets the expanded org-level read; walletreg keeps the higher explicit write
    assertEquals(Map.of("demo", RIGHT_READ, "walletreg", RIGHT_WRITE), functionRights(entry));
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
   * Test 7: An org group with no Swedish display name. Display names are optional, so the mapper
   * simply omits that claim member; the legal name is unaffected.
   */
  @Test
  void testMissingOrgAttributes() {
    when(realm.getRole(REALM_ROLE_SUPERUSER)).thenReturn(null);

    final GroupModel orgsGroup = mockGroup("orgs-id", GROUP_ORGS, null);
    when(realm.getTopLevelGroupsStream()).thenReturn(Stream.of(orgsGroup));
    when(orgsGroup.getId()).thenReturn("orgs-id");

    final GroupModel orgGroup = mockGroup("org1-id", "2021006883", "orgs-id");
    when(orgsGroup.getSubGroupsStream()).thenReturn(Stream.of(orgGroup));
    // ATTR_ORGANIZATION_NAME_SV is intentionally absent
    when(orgGroup.getAttributes()).thenReturn(Map.of(
        ATTR_ORGANIZATION_IDENTIFIER, List.of("2021006883"),
        ATTR_ORGANIZATION_NAME,       List.of("Myndigheten för Digital förvaltning"),
        ATTR_ORGANIZATION_NAME_EN,    List.of("Digg - Myndigheten för Digital förvaltning")));
    when(orgGroup.getParentId()).thenReturn("orgs-id");
    mockOrgChildren(orgGroup, "demo", RIGHT_GROUP_ADMIN, RIGHT_GROUP_WRITE, RIGHT_GROUP_READ);

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

    final Map<String, Object> entry = orgRights.get(0);
    assertNull(entry.get(ATTR_ORGANIZATION_NAME_SV));
    assertEquals("Digg - Myndigheten för Digital förvaltning", entry.get(ATTR_ORGANIZATION_NAME_EN));
    assertEquals("Myndigheten för Digital förvaltning",        entry.get(CLAIM_FIELD_ORGANIZATION_LEGAL_NAME));
    assertEquals("Myndigheten för Digital förvaltning",        entry.get(ATTR_ORGANIZATION_NAME));
  }

  /**
   * An organization with no display names at all: the claim carries the legal name under both
   * {@code organization_legal_name} and the untagged {@code organization_name}, and neither tagged
   * key is present.
   */
  @Test
  void testLegalNameOnly_noDisplayNamesEmitted() {
    final Map<String, Object> entry = singleOrgEntry(Map.of(
        ATTR_ORGANIZATION_IDENTIFIER, List.of("2021006883"),
        ATTR_ORGANIZATION_NAME,       List.of("Myndigheten för Digital förvaltning")));

    assertEquals("Myndigheten för Digital förvaltning", entry.get(CLAIM_FIELD_ORGANIZATION_LEGAL_NAME));
    assertEquals("Myndigheten för Digital förvaltning", entry.get(ATTR_ORGANIZATION_NAME));
    assertFalse(entry.containsKey(ATTR_ORGANIZATION_NAME_SV));
    assertFalse(entry.containsKey(ATTR_ORGANIZATION_NAME_EN));
  }

  /**
   * Backfill: an org group created before the legal name existed carries only tagged names. The
   * Swedish display name is used as the legal name, and it is not written back to the group.
   */
  @Test
  void testLegalNameBackfilledFromSwedishDisplayName() {
    final Map<String, Object> entry = singleOrgEntry(Map.of(
        ATTR_ORGANIZATION_IDENTIFIER, List.of("2021006883"),
        ATTR_ORGANIZATION_NAME_SV,    List.of("Digg - Myndigheten för Digital förvaltning"),
        ATTR_ORGANIZATION_NAME_EN,    List.of("Digg - Authority for Digital Government")));

    assertEquals("Digg - Myndigheten för Digital förvaltning", entry.get(CLAIM_FIELD_ORGANIZATION_LEGAL_NAME));
    assertEquals("Digg - Myndigheten för Digital förvaltning", entry.get(ATTR_ORGANIZATION_NAME));
    assertEquals("Digg - Myndigheten för Digital förvaltning", entry.get(ATTR_ORGANIZATION_NAME_SV));
    assertEquals("Digg - Authority for Digital Government", entry.get(ATTR_ORGANIZATION_NAME_EN));
  }

  /** Backfill falls through to the English display name when there is no Swedish one. */
  @Test
  void testLegalNameBackfilledFromEnglishDisplayName() {
    final Map<String, Object> entry = singleOrgEntry(Map.of(
        ATTR_ORGANIZATION_IDENTIFIER, List.of("2021006883"),
        ATTR_ORGANIZATION_NAME_EN,    List.of("Digg - Authority for Digital Government")));

    assertEquals("Digg - Authority for Digital Government", entry.get(CLAIM_FIELD_ORGANIZATION_LEGAL_NAME));
    assertEquals("Digg - Authority for Digital Government", entry.get(ATTR_ORGANIZATION_NAME));
    assertFalse(entry.containsKey(ATTR_ORGANIZATION_NAME_SV));
  }

  /** A group with no name at all is malformed; the organization identifier is used instead. */
  @Test
  void testNoNameAtAll_fallsBackToOrgIdentifier() {
    final Map<String, Object> entry = singleOrgEntry(Map.of(
        ATTR_ORGANIZATION_IDENTIFIER, List.of("2021006883")));

    assertEquals("2021006883", entry.get(CLAIM_FIELD_ORGANIZATION_LEGAL_NAME));
    assertEquals("2021006883", entry.get(ATTR_ORGANIZATION_NAME));
    assertFalse(entry.containsKey(ATTR_ORGANIZATION_NAME_SV));
    assertFalse(entry.containsKey(ATTR_ORGANIZATION_NAME_EN));
  }

  // ---- helpers ----

  /**
   * Runs the mapper for a user holding org-level write on a single organization whose group carries
   * the given attributes, and returns the one claim entry produced.
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> singleOrgEntry(final Map<String, List<String>> orgAttributes) {
    when(realm.getRole(REALM_ROLE_SUPERUSER)).thenReturn(null);

    final GroupModel orgsGroup = mockGroup("orgs-id", GROUP_ORGS, null);
    when(realm.getTopLevelGroupsStream()).thenReturn(Stream.of(orgsGroup));
    when(orgsGroup.getId()).thenReturn("orgs-id");

    final GroupModel orgGroup = mockGroup("org1-id", "2021006883", "orgs-id");
    when(orgsGroup.getSubGroupsStream()).thenReturn(Stream.of(orgGroup));
    when(orgGroup.getAttributes()).thenReturn(Map.copyOf(orgAttributes));
    when(orgGroup.getParentId()).thenReturn("orgs-id");
    mockOrgChildren(orgGroup, "demo", RIGHT_GROUP_ADMIN, RIGHT_GROUP_WRITE, RIGHT_GROUP_READ);

    final GroupModel writeGroup = mockGroup("org-write-id", RIGHT_GROUP_WRITE, "org1-id");
    when(writeGroup.getParent()).thenReturn(orgGroup);
    when(user.getGroupsStream()).thenReturn(Stream.of(writeGroup));

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    final List<Map<String, Object>> orgRights =
        (List<Map<String, Object>>) token.getOtherClaims().get(CLAIM_NAME);
    assertNotNull(orgRights);
    assertEquals(1, orgRights.size());
    return orgRights.get(0);
  }

  private GroupModel mockGroup(final String id, final String name, final String parentId) {
    final GroupModel g = mock(GroupModel.class);
    when(g.getId()).thenReturn(id);
    when(g.getName()).thenReturn(name);
    when(g.getParentId()).thenReturn(parentId);
    return g;
  }

  /**
   * Stubs the sub-groups of an org group — the attached function groups plus the org's own right
   * groups. A fresh stream is answered on every call so repeated reads are safe.
   */
  private void mockOrgChildren(final GroupModel orgGroup, final String... childNames) {
    when(orgGroup.getSubGroupsStream()).thenAnswer(invocation -> Stream.of(childNames)
        .map(name -> mockGroup(orgGroup.getId() + "-" + name, name, orgGroup.getId())));
  }

  /** Reduces an org entry's functions array to a function-to-right map for easier assertions. */
  private static Map<String, String> functionRights(final Map<String, Object> orgEntry) {
    @SuppressWarnings("unchecked")
    final List<Map<String, String>> functions =
        (List<Map<String, String>>) orgEntry.get(CLAIM_FIELD_FUNCTIONS);
    assertNotNull(functions);
    return functions.stream().collect(
        Collectors.toMap(f -> f.get(CLAIM_FIELD_FUNCTION), f -> f.get(CLAIM_FIELD_RIGHT)));
  }
}
