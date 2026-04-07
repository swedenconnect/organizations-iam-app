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

import org.jboss.logging.Logger;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A Keycloak OIDC protocol mapper that adds the {@code org_rights} claim to tokens. The claim
 * describes all rights the user holds across organizations and functions, derived entirely from
 * the user's group memberships in the Keycloak group tree under the top-level {@code orgs} group.
 *
 * @author Martin Lindström
 */
public class OrgRightsMapper extends AbstractOIDCProtocolMapper
    implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper {

  private static final Logger LOG = Logger.getLogger(OrgRightsMapper.class);

  // ---- Mapper identity ----

  public static final String PROVIDER_ID = "org-rights-mapper";
  public static final String DISPLAY_TYPE = "Org Rights Mapper";
  public static final String DISPLAY_CATEGORY = AbstractOIDCProtocolMapper.TOKEN_MAPPER_CATEGORY;
  public static final String HELP_TEXT =
      "Adds the org_rights claim describing the user's rights across organizations and functions.";

  // ---- Token claim name ----

  /** The name of the claim added to the token. */
  public static final String CLAIM_NAME = "org_rights";

  // ---- Realm role ----

  /** Name of the Keycloak realm role that grants full superuser access. */
  public static final String REALM_ROLE_SUPERUSER = "superuser";

  // ---- Top-level group name ----

  /** Name of the top-level Keycloak group that contains all organization groups. */
  public static final String GROUP_ORGS = "orgs";

  // ---- Right group names (children of org groups or function sub-groups) ----

  /** Group name representing the admin right level. */
  public static final String RIGHT_GROUP_ADMIN = "_admin";

  /** Group name representing the write right level. */
  public static final String RIGHT_GROUP_WRITE = "_write";

  /** Group name representing the read right level. */
  public static final String RIGHT_GROUP_READ = "_read";

  // ---- Right strings used in claim values ----

  /** Claim value for the admin right. */
  public static final String RIGHT_ADMIN = "admin";

  /** Claim value for the write right. */
  public static final String RIGHT_WRITE = "write";

  /** Claim value for the read right. */
  public static final String RIGHT_READ = "read";

  // ---- Organization group attribute keys (also used as claim field names) ----

  /** Group attribute and claim field: the ten-digit organization number. */
  public static final String ATTR_ORGANIZATION_IDENTIFIER = "organization_identifier";

  /** Group attribute and claim field: the organization name in Swedish. */
  public static final String ATTR_ORGANIZATION_NAME_SV = "organization_name#sv";

  /** Group attribute and claim field: the organization name in English. */
  public static final String ATTR_ORGANIZATION_NAME_EN = "organization_name#en";

  // ---- Claim entry field keys ----

  /** Claim entry field: the list of function-right objects for this organization. */
  public static final String CLAIM_FIELD_FUNCTIONS = "functions";

  /**
   * Claim entry field (inside each element of {@link #CLAIM_FIELD_FUNCTIONS}):
   * the function name, or {@link #FUNCTION_WILDCARD} for an org-level right.
   */
  public static final String CLAIM_FIELD_FUNCTION = "function";

  /**
   * Claim entry field (inside each element of {@link #CLAIM_FIELD_FUNCTIONS}):
   * the right level ("admin", "write", or "read").
   */
  public static final String CLAIM_FIELD_RIGHT = "right";

  /** Claim entry field: the superuser flag, used in the superuser shortcut entry. */
  public static final String CLAIM_FIELD_SUPERUSER = "superuser";

  // ---- Special function value ----

  /**
   * Wildcard function name used in the {@link #CLAIM_FIELD_FUNCTIONS} array to indicate
   * that a right was granted at the organization level (covering all functions).
   */
  public static final String FUNCTION_WILDCARD = "*";

  // -------------------------------------------------------------------------

  @Override
  public String getId() {
    return PROVIDER_ID;
  }

  @Override
  public String getDisplayType() {
    return DISPLAY_TYPE;
  }

  @Override
  public String getDisplayCategory() {
    return DISPLAY_CATEGORY;
  }

  @Override
  public String getHelpText() {
    return HELP_TEXT;
  }

  @Override
  public List<ProviderConfigProperty> getConfigProperties() {
    return new ArrayList<>();
  }

  @Override
  protected void setClaim(
      final IDToken token,
      final ProtocolMapperModel mappingModel,
      final UserSessionModel userSession,
      final KeycloakSession keycloakSession,
      final ClientSessionContext clientSessionCtx) {

    final UserModel user = userSession.getUser();
    final RealmModel realm = keycloakSession.getContext().getRealm();

    // Step 1 — Check for superuser
    final RoleModel superuserRole = realm.getRole(REALM_ROLE_SUPERUSER);
    if (superuserRole != null && user.hasRole(superuserRole)) {
      final Map<String, Object> superuserEntry = new LinkedHashMap<>();
      superuserEntry.put(CLAIM_FIELD_SUPERUSER, true);
      token.getOtherClaims().put(CLAIM_NAME, List.of(superuserEntry));
      return;
    }

    // Step 2 — Find the `orgs` top-level group
    final GroupModel orgsGroup = realm.getTopLevelGroupsStream()
        .filter(g -> GROUP_ORGS.equals(g.getName()))
        .findFirst()
        .orElse(null);

    if (orgsGroup == null) {
      token.getOtherClaims().put(CLAIM_NAME, List.of());
      return;
    }

    // Step 3 — Get all groups the user belongs to
    final Set<GroupModel> userGroups = user.getGroupsStream().collect(Collectors.toSet());

    if (userGroups.isEmpty()) {
      token.getOtherClaims().put(CLAIM_NAME, List.of());
      return;
    }

    // Step 4 — Classify group memberships and accumulate function entries per org.
    // key   = org identifier (group name under orgs)
    // value = list of { "function": <name|"*">, "right": <right> } maps
    final Map<String, List<Map<String, String>>> orgFunctionEntries = new LinkedHashMap<>();

    for (final GroupModel group : userGroups) {
      final String right = rightFromGroupName(group.getName());
      if (right == null) {
        continue;
      }

      // The group is a right group (_admin/_write/_read).
      // Determine depth from `orgs` by walking up the parent chain.
      final GroupModel parent = group.getParent();
      if (parent == null) {
        continue;
      }

      if (parent.getParentId() != null && parent.getParentId().equals(orgsGroup.getId())) {
        // parent is a direct child of orgs → org-level right; wildcard function
        final String orgIdentifier = parent.getName();
        LOG.debugf("User has org-level right '%s' on org '%s'", right, orgIdentifier);
        orgFunctionEntries
            .computeIfAbsent(orgIdentifier, k -> new ArrayList<>())
            .add(functionEntry(FUNCTION_WILDCARD, right));
      }
      else {
        // Check if grandparent is a direct child of orgs → function-level right
        final GroupModel grandParent = parent.getParent();
        if (grandParent == null) {
          continue;
        }
        if (grandParent.getParentId() != null && grandParent.getParentId().equals(orgsGroup.getId())) {
          final String orgIdentifier = grandParent.getName();
          final String functionName = parent.getName();
          LOG.debugf("User has function-level right '%s' on org '%s', function '%s'",
              right, orgIdentifier, functionName);
          orgFunctionEntries
              .computeIfAbsent(orgIdentifier, k -> new ArrayList<>())
              .add(functionEntry(functionName, right));
        }
      }
    }

    // Step 5 — Build one claim entry per organization
    final List<Map<String, Object>> entries = new ArrayList<>();

    for (final Map.Entry<String, List<Map<String, String>>> e : orgFunctionEntries.entrySet()) {
      final String orgIdentifier = e.getKey();
      final List<Map<String, String>> functionEntries = e.getValue();

      final GroupModel orgGroup = orgsGroup.getSubGroupsStream()
          .filter(g -> orgIdentifier.equals(g.getName()))
          .findFirst()
          .orElse(null);

      final String orgNameSv = readOrgAttribute(orgGroup, ATTR_ORGANIZATION_NAME_SV, orgIdentifier);
      final String orgNameEn = readOrgAttribute(orgGroup, ATTR_ORGANIZATION_NAME_EN, orgIdentifier);
      final String orgId     = readOrgAttribute(orgGroup, ATTR_ORGANIZATION_IDENTIFIER, orgIdentifier);

      final Map<String, Object> entry = new LinkedHashMap<>();
      entry.put(ATTR_ORGANIZATION_IDENTIFIER, orgId);
      entry.put(ATTR_ORGANIZATION_NAME_SV, orgNameSv);
      entry.put(ATTR_ORGANIZATION_NAME_EN, orgNameEn);
      entry.put(CLAIM_FIELD_FUNCTIONS, functionEntries);
      entries.add(entry);
    }

    token.getOtherClaims().put(CLAIM_NAME, entries);
  }

  /**
   * Converts a right group name to the corresponding right string used in claim values.
   *
   * @param name the group name
   * @return {@link #RIGHT_ADMIN}, {@link #RIGHT_WRITE}, {@link #RIGHT_READ},
   *         or {@code null} if the name is not a right group
   */
  static String rightFromGroupName(final String name) {
    return switch (name) {
      case RIGHT_GROUP_ADMIN -> RIGHT_ADMIN;
      case RIGHT_GROUP_WRITE -> RIGHT_WRITE;
      case RIGHT_GROUP_READ  -> RIGHT_READ;
      default -> null;
    };
  }

  /**
   * Builds a single function-right entry for the {@link #CLAIM_FIELD_FUNCTIONS} array.
   *
   * @param functionName the function name, or {@link #FUNCTION_WILDCARD} for an org-level right
   * @param right the right string ({@link #RIGHT_ADMIN}, {@link #RIGHT_WRITE}, or {@link #RIGHT_READ})
   * @return a two-key map with {@link #CLAIM_FIELD_FUNCTION} and {@link #CLAIM_FIELD_RIGHT}
   */
  private static Map<String, String> functionEntry(final String functionName, final String right) {
    final Map<String, String> entry = new LinkedHashMap<>();
    entry.put(CLAIM_FIELD_FUNCTION, functionName);
    entry.put(CLAIM_FIELD_RIGHT, right);
    return entry;
  }

  /**
   * Reads the first value of a named attribute from a group. Returns an empty string (with a
   * warning log) if the group is {@code null} or the attribute is absent.
   *
   * @param group the group to read from, may be {@code null}
   * @param attributeKey the attribute key
   * @param orgIdentifier the org identifier used in log messages
   * @return the first attribute value, or empty string if absent
   */
  private String readOrgAttribute(final GroupModel group, final String attributeKey,
      final String orgIdentifier) {
    if (group == null) {
      LOG.warnf("Organization group '%s' not found under '%s' — using empty string for attribute '%s'",
          orgIdentifier, GROUP_ORGS, attributeKey);
      return "";
    }
    final List<String> values = group.getAttributes().get(attributeKey);
    if (values == null || values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) {
      LOG.warnf("Organization group '%s' is missing attribute '%s' — using empty string",
          orgIdentifier, attributeKey);
      return "";
    }
    return values.get(0);
  }
}
