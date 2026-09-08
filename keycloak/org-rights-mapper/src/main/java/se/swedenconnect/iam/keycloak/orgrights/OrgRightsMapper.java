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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A Keycloak OIDC protocol mapper that adds the {@code org_rights} claim to tokens. The claim
 * describes all rights the user holds across organizations and functions, derived entirely from
 * the user's group memberships in the Keycloak group tree under the top-level {@code orgs} group.
 *
 * <p>A right granted at the organization level (membership in {@code orgs/{orgId}/_admin},
 * {@code /_write} or {@code /_read}) is expanded into one entry per function <em>currently
 * attached</em> to that organization, and additionally recorded as
 * {@link #CLAIM_FIELD_ORG_LEVEL_RIGHT} for provenance. Consumers therefore never have to resolve
 * the attachment set themselves.</p>
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

  /**
   * Group attribute and claim field: the organization's legal name, as registered at Bolagsverket.
   * Carries no language tag. Always present on the group and always emitted in the claim.
   */
  public static final String ATTR_ORGANIZATION_NAME = "organization_name";

  /**
   * Group attribute and claim field: the organization display name in Swedish. Optional — the
   * attribute is absent when no Swedish display name has been given, and the claim member is then
   * not emitted.
   */
  public static final String ATTR_ORGANIZATION_NAME_SV = "organization_name#sv";

  /**
   * Group attribute and claim field: the organization display name in English. Optional, in the
   * same way as {@link #ATTR_ORGANIZATION_NAME_SV}.
   */
  public static final String ATTR_ORGANIZATION_NAME_EN = "organization_name#en";

  /**
   * Claim entry field: the organization's legal name. Always present, and the member consumers are
   * meant to read when they want the registered name. {@link #ATTR_ORGANIZATION_NAME} carries the
   * same value and exists only so a consumer doing a language lookup across the
   * {@code organization_name*} members still resolves to something.
   */
  public static final String CLAIM_FIELD_ORGANIZATION_LEGAL_NAME = "organization_legal_name";

  // ---- Claim entry field keys ----

  /** Claim entry field: the list of function-right objects for this organization. */
  public static final String CLAIM_FIELD_FUNCTIONS = "functions";

  /**
   * Claim entry field (inside each element of {@link #CLAIM_FIELD_FUNCTIONS}):
   * the name of a function attached to the organization.
   */
  public static final String CLAIM_FIELD_FUNCTION = "function";

  /**
   * Claim entry field (inside each element of {@link #CLAIM_FIELD_FUNCTIONS}):
   * the right level ("admin", "write", or "read").
   */
  public static final String CLAIM_FIELD_RIGHT = "right";

  /**
   * Claim entry field: the right the user was granted at the organization level, if any.
   *
   * <p>This field records <em>how</em> a right was granted — it is provenance only and confers
   * no access by itself. Effective rights are given exclusively by {@link #CLAIM_FIELD_FUNCTIONS},
   * into which an org-level right has already been expanded (one entry per attached function).
   * The field is absent when the user holds no org-level right.</p>
   */
  public static final String CLAIM_FIELD_ORG_LEVEL_RIGHT = "org_level_right";

  /** Claim entry field: the superuser flag, used in the superuser shortcut entry. */
  public static final String CLAIM_FIELD_SUPERUSER = "superuser";

  // -------------------------------------------------------------------------

  @Override
  public @NonNull String getId() {
    return PROVIDER_ID;
  }

  @Override
  public @NonNull String getDisplayType() {
    return DISPLAY_TYPE;
  }

  @Override
  public @NonNull String getDisplayCategory() {
    return DISPLAY_CATEGORY;
  }

  @Override
  public @NonNull String getHelpText() {
    return HELP_TEXT;
  }

  @Override
  public @NonNull List<ProviderConfigProperty> getConfigProperties() {
    return List.of();
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

    LOG.debugf("[org-rights] Building %s claim for user '%s'", CLAIM_NAME, user.getUsername());

    // Step 1 — Check for superuser
    final RoleModel superuserRole = realm.getRole(REALM_ROLE_SUPERUSER);
    if (superuserRole != null && user.hasRole(superuserRole)) {
      LOG.debugf("[org-rights] User '%s' has realm role '%s' — emitting superuser shortcut",
          user.getUsername(), REALM_ROLE_SUPERUSER);
      final Map<String, Object> superuserEntry = new LinkedHashMap<>();
      superuserEntry.put(CLAIM_FIELD_SUPERUSER, true);
      token.getOtherClaims().put(CLAIM_NAME, List.of(superuserEntry));
      return;
    }
    LOG.debugf("[org-rights] User '%s' is not a superuser", user.getUsername());

    // Step 2 — Find the `orgs` top-level group
    final GroupModel orgsGroup = realm.getTopLevelGroupsStream()
        .filter(g -> GROUP_ORGS.equals(g.getName()))
        .findFirst()
        .orElse(null);

    if (orgsGroup == null) {
      LOG.debugf("[org-rights] Top-level group '%s' not found in realm — emitting empty claim", GROUP_ORGS);
      token.getOtherClaims().put(CLAIM_NAME, List.of());
      return;
    }
    LOG.debugf("[org-rights] Found top-level group '%s' (id=%s)", GROUP_ORGS, orgsGroup.getId());

    // Step 3 — Get all groups the user belongs to, preserving Keycloak's iteration order
    final LinkedHashSet<GroupModel> userGroups =
        user.getGroupsStream().collect(Collectors.toCollection(LinkedHashSet::new));

    LOG.debugf("[org-rights] User '%s' belongs to %d group(s)", user.getUsername(), userGroups.size());

    if (userGroups.isEmpty()) {
      token.getOtherClaims().put(CLAIM_NAME, List.of());
      return;
    }

    // Step 4 — Classify group memberships and accumulate rights per org.
    // key   = org group name (= group name directly under orgs)
    // value = the org-level right (if any) plus the function-level rights, highest per function
    final Map<String, OrgRights> orgRights = new LinkedHashMap<>();

    for (final GroupModel group : userGroups) {
      LOG.debugf("[org-rights] Inspecting group '%s' (id=%s)", group.getName(), group.getId());

      final String right = rightFromGroupName(group.getName());
      if (right == null) {
        LOG.debugf("[org-rights]   Skipping '%s' — not a right group", group.getName());
        continue;
      }

      // The group is a right group (_admin/_write/_read).
      // Determine depth from `orgs` by walking up the parent chain.
      final GroupModel parent = group.getParent();
      if (parent == null) {
        LOG.debugf("[org-rights]   Skipping '%s' — no parent (orphaned right group)", group.getName());
        continue;
      }

      LOG.debugf("[org-rights]   Parent of '%s' is '%s' (parentId=%s)",
          group.getName(), parent.getName(), parent.getParentId());

      if (parent.getParentId() != null && parent.getParentId().equals(orgsGroup.getId())) {
        // parent is a direct child of orgs → org-level right. The set of functions to expand it
        // onto is not known until the org group is loaded in Step 5, so only record it here.
        final String orgIdentifier = parent.getName();
        LOG.debugf("[org-rights]   Resolved to org-level right '%s' on org '%s'", right, orgIdentifier);
        final OrgRights rights = orgRights.computeIfAbsent(orgIdentifier, k -> new OrgRights());
        rights.orgLevelRight = highestRight(rights.orgLevelRight, right);
      }
      else {
        // Check if grandparent is a direct child of orgs → function-level right
        final GroupModel grandParent = parent.getParent();
        if (grandParent == null) {
          LOG.debugf("[org-rights]   Skipping '%s' — grandparent is null, group not under '%s'",
              group.getName(), GROUP_ORGS);
          continue;
        }
        LOG.debugf("[org-rights]   Grandparent of '%s' is '%s' (parentId=%s)",
            group.getName(), grandParent.getName(), grandParent.getParentId());

        if (grandParent.getParentId() != null && grandParent.getParentId().equals(orgsGroup.getId())) {
          final String orgIdentifier = grandParent.getName();
          final String functionName = parent.getName();
          LOG.debugf("[org-rights]   Resolved to function-level right '%s' on org '%s', function '%s'",
              right, orgIdentifier, functionName);
          orgRights
              .computeIfAbsent(orgIdentifier, k -> new OrgRights())
              .functionRights.merge(functionName, right, OrgRightsMapper::highestRight);
        }
        else {
          LOG.debugf("[org-rights]   Skipping '%s' — hierarchy too deep or not under '%s'",
              group.getName(), GROUP_ORGS);
        }
      }
    }

    LOG.debugf("[org-rights] Classified memberships into %d org(s): %s",
        orgRights.size(), orgRights.keySet());

    // Step 5 — Build one claim entry per organization, expanding org-level rights onto the
    // functions currently attached to the organization.
    // Collect org sub-groups once to avoid one getSubGroupsStream() call per org.
    final Map<String, GroupModel> orgGroupByName = orgsGroup.getSubGroupsStream()
        .collect(Collectors.toMap(GroupModel::getName, g -> g));
    LOG.debugf("[org-rights] Loaded %d org sub-group(s) from '%s'", orgGroupByName.size(), GROUP_ORGS);

    final List<Map<String, Object>> entries = new ArrayList<>();

    for (final Map.Entry<String, OrgRights> e : orgRights.entrySet()) {
      final String orgIdentifier = e.getKey();
      final OrgRights rights = e.getValue();

      final GroupModel orgGroup = orgGroupByName.get(orgIdentifier);
      if (orgGroup == null) {
        LOG.debugf("[org-rights] Org group '%s' not found in lookup map — attributes will be empty",
            orgIdentifier);
      }

      final String orgNameSv = readOptionalOrgAttribute(orgGroup, ATTR_ORGANIZATION_NAME_SV);
      final String orgNameEn = readOptionalOrgAttribute(orgGroup, ATTR_ORGANIZATION_NAME_EN);
      final String legalName = resolveLegalName(orgGroup, orgNameSv, orgNameEn, orgIdentifier);
      final String orgId     = readOrgAttribute(orgGroup, ATTR_ORGANIZATION_IDENTIFIER, orgIdentifier);

      // Effective right per function: the org-level right expanded onto every attached function,
      // then raised by any explicit function-level right the user holds.
      final Map<String, String> effectiveRights = new LinkedHashMap<>();
      final String orgLevelRight = rights.orgLevelRight;
      if (orgLevelRight != null) {
        for (final String functionId : attachedFunctions(orgGroup, orgIdentifier)) {
          effectiveRights.put(functionId, orgLevelRight);
        }
        LOG.debugf("[org-rights] Expanded org-level right '%s' on org '%s' onto %d attached function(s)",
            orgLevelRight, orgIdentifier, effectiveRights.size());
      }
      rights.functionRights.forEach((f, r) -> effectiveRights.merge(f, r, OrgRightsMapper::highestRight));

      final List<Map<String, String>> functionEntries = effectiveRights.entrySet().stream()
          .map(fr -> functionEntry(fr.getKey(), fr.getValue()))
          .toList();

      LOG.debugf("[org-rights] Building entry for org '%s' (id=%s, legal='%s', sv='%s', en='%s', %s=%s) "
              + "with %d function(s): %s",
          orgIdentifier, orgId, legalName, orgNameSv, orgNameEn, CLAIM_FIELD_ORG_LEVEL_RIGHT, orgLevelRight,
          functionEntries.size(), functionEntries);

      final Map<String, Object> entry = new LinkedHashMap<>();
      entry.put(ATTR_ORGANIZATION_IDENTIFIER, orgId);
      entry.put(CLAIM_FIELD_ORGANIZATION_LEGAL_NAME, legalName);
      // The untagged member repeats the legal name so that a consumer doing a language lookup
      // across organization_name* still resolves to something. Backwards compatibility only.
      entry.put(ATTR_ORGANIZATION_NAME, legalName);
      if (orgNameSv != null) {
        entry.put(ATTR_ORGANIZATION_NAME_SV, orgNameSv);
      }
      if (orgNameEn != null) {
        entry.put(ATTR_ORGANIZATION_NAME_EN, orgNameEn);
      }
      if (orgLevelRight != null) {
        entry.put(CLAIM_FIELD_ORG_LEVEL_RIGHT, orgLevelRight);
      }
      entry.put(CLAIM_FIELD_FUNCTIONS, functionEntries);
      entries.add(entry);
    }

    LOG.debugf("[org-rights] Emitting '%s' claim with %d org entr%s for user '%s'",
        CLAIM_NAME, entries.size(), entries.size() == 1 ? "y" : "ies", user.getUsername());

    token.getOtherClaims().put(CLAIM_NAME, entries);
  }

  /**
   * Converts a right group name to the corresponding right string used in claim values.
   *
   * @param name the group name
   * @return {@link #RIGHT_ADMIN}, {@link #RIGHT_WRITE}, {@link #RIGHT_READ},
   *         or {@code null} if the name is not a right group
   */
  static @Nullable String rightFromGroupName(final @NonNull String name) {
    return switch (name) {
      case RIGHT_GROUP_ADMIN -> RIGHT_ADMIN;
      case RIGHT_GROUP_WRITE -> RIGHT_WRITE;
      case RIGHT_GROUP_READ  -> RIGHT_READ;
      default -> null;
    };
  }

  /**
   * Returns the rank of a right, used to resolve the highest right when several apply to the same
   * function. Rights are hierarchical: {@code admin} &gt; {@code write} &gt; {@code read}.
   *
   * @param right the right string
   * @return the rank, or {@code 0} for an unrecognized right
   */
  private static int rightRank(final @NonNull String right) {
    return switch (right) {
      case RIGHT_ADMIN -> 3;
      case RIGHT_WRITE -> 2;
      case RIGHT_READ  -> 1;
      default -> 0;
    };
  }

  /**
   * Returns the higher of two rights.
   *
   * @param current the right resolved so far, or {@code null} if none
   * @param candidate the right to compare against
   * @return {@code candidate} if it outranks {@code current}, otherwise {@code current}
   */
  private static @NonNull String highestRight(
      final @Nullable String current, final @NonNull String candidate) {
    return current == null || rightRank(candidate) > rightRank(current) ? candidate : current;
  }

  /**
   * Returns the names of the functions currently attached to an organization.
   *
   * <p>Attachments are the org group's sub-groups, identified by exclusion: everything that is not
   * one of the three reserved right groups ({@link #RIGHT_GROUP_ADMIN}, {@link #RIGHT_GROUP_WRITE},
   * {@link #RIGHT_GROUP_READ}). Matching the reserved names exactly rather than filtering on a
   * leading underscore keeps function identifiers such as {@code _foo} — legal per the admin
   * application's {@code [a-z0-9_-]+} rule — from being silently dropped. The {@code function_ref}
   * attribute each attachment sub-group carries is deliberately not required: reading it would cost
   * one attribute load per sub-group, and a hand-provisioned realm that omitted it would silently
   * lose the user's rights.</p>
   *
   * @param orgGroup the organization group, may be {@code null} if it could not be resolved
   * @param orgIdentifier the org identifier used in log messages
   * @return the attached function names, in Keycloak's iteration order; never {@code null}
   */
  private @NonNull List<String> attachedFunctions(
      final @Nullable GroupModel orgGroup, final @NonNull String orgIdentifier) {

    if (orgGroup == null) {
      LOG.debugf("[org-rights] Cannot resolve attached functions for org '%s' — org group not found",
          orgIdentifier);
      return List.of();
    }
    final List<String> functions = orgGroup.getSubGroupsStream()
        .map(GroupModel::getName)
        .filter(name -> name != null && rightFromGroupName(name) == null)
        .toList();
    LOG.debugf("[org-rights] Org '%s' has %d attached function(s): %s",
        orgIdentifier, functions.size(), functions);
    return functions;
  }

  /**
   * Builds a single function-right entry for the {@link #CLAIM_FIELD_FUNCTIONS} array.
   *
   * @param functionName the name of a function attached to the organization
   * @param right the right string ({@link #RIGHT_ADMIN}, {@link #RIGHT_WRITE}, or {@link #RIGHT_READ})
   * @return a two-key map with {@link #CLAIM_FIELD_FUNCTION} and {@link #CLAIM_FIELD_RIGHT}
   */
  private static @NonNull Map<String, String> functionEntry(
      final @NonNull String functionName, final @NonNull String right) {
    final Map<String, String> entry = new LinkedHashMap<>();
    entry.put(CLAIM_FIELD_FUNCTION, functionName);
    entry.put(CLAIM_FIELD_RIGHT, right);
    return entry;
  }

  /**
   * Reads the first value of an optional attribute from a group. Unlike
   * {@link #readOrgAttribute(GroupModel, String, String)} an absent attribute is a normal outcome
   * and is not logged, because display names are optional.
   *
   * @param group the group to read from, may be {@code null}
   * @param attributeKey the attribute key
   * @return the first attribute value, or {@code null} if the group or the attribute is absent
   */
  private static @Nullable String readOptionalOrgAttribute(
      final @Nullable GroupModel group, final @NonNull String attributeKey) {
    if (group == null) {
      return null;
    }
    final List<String> values = group.getAttributes().get(attributeKey);
    if (values == null || values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()) {
      return null;
    }
    return values.getFirst();
  }

  /**
   * Resolves an organization's legal name from its group.
   *
   * <p>The untagged {@link #ATTR_ORGANIZATION_NAME} attribute is the legal name. A group created
   * before the legal name existed does not carry it, so the Swedish display name is used, then the
   * English one, and the fact is logged: a human has to enter the real registered name. The derived
   * value is not written back. A group carrying neither is malformed, and the organization
   * identifier is used so that nothing downstream breaks.</p>
   *
   * @param group the organization group, may be {@code null}
   * @param orgNameSv the Swedish display name, or {@code null}
   * @param orgNameEn the English display name, or {@code null}
   * @param orgIdentifier the organization identifier, used in log messages and as last resort
   * @return the legal name; never {@code null}
   */
  private static @NonNull String resolveLegalName(
      final @Nullable GroupModel group,
      final @Nullable String orgNameSv,
      final @Nullable String orgNameEn,
      final @NonNull String orgIdentifier) {

    final String legalName = readOptionalOrgAttribute(group, ATTR_ORGANIZATION_NAME);
    if (legalName != null) {
      return legalName;
    }
    if (orgNameSv != null || orgNameEn != null) {
      LOG.warnf("[org-rights] Organization '%s' has no '%s' attribute — its legal name has not been "
              + "entered and needs to be updated; using a display name in the meantime",
          orgIdentifier, ATTR_ORGANIZATION_NAME);
      return orgNameSv != null ? orgNameSv : orgNameEn;
    }
    LOG.errorf("[org-rights] Organization group '%s' carries no name at all — neither '%s' nor a "
            + "display name; using the organization identifier as its name",
        orgIdentifier, ATTR_ORGANIZATION_NAME);
    return orgIdentifier;
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
  private @NonNull String readOrgAttribute(final @Nullable GroupModel group,
      final @NonNull String attributeKey, final @NonNull String orgIdentifier) {
    if (group == null) {
      LOG.warnf("[org-rights] Organization group '%s' not found under '%s' — using empty string for attribute '%s'",
          orgIdentifier, GROUP_ORGS, attributeKey);
      return "";
    }
    final List<String> values = group.getAttributes().get(attributeKey);
    if (values == null || values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()) {
      LOG.warnf("[org-rights] Organization group '%s' is missing attribute '%s' — using empty string",
          orgIdentifier, attributeKey);
      return "";
    }
    return values.getFirst();
  }

  /**
   * The rights a user holds within a single organization, as accumulated from the user's group
   * memberships before the org-level right is expanded onto the organization's attached functions.
   */
  private static final class OrgRights {

    /** The highest right granted at the organization level, or {@code null} if none. */
    private @Nullable String orgLevelRight;

    /** The highest right granted per named function, keyed by function name. */
    private final @NonNull Map<String, String> functionRights = new LinkedHashMap<>();
  }
}