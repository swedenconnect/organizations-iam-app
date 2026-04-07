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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.jspecify.annotations.Nullable;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.FunctionInfo;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;
import se.swedenconnect.iam.admin.keycloak.model.UserInfo;
import se.swedenconnect.iam.admin.keycloak.model.UserRight;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates the KeyCloak Admin REST API calls required to populate an
 * {@link AdminSessionData} after a successful OIDC login.
 *
 * <p>Superusers receive all functions, all organizations, and all users. Regular admins receive
 * only the functions, organizations, and users that fall within their authorized scope as
 * expressed by the {@code org_rights} claim.</p>
 *
 * @author Martin Lindström
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AdminDataBootstrapService {

  private final KeycloakAdminClient keycloakAdminClient;
  private final ObjectMapper objectMapper;

  /**
   * Fetches and returns the session data for the authenticated user.
   *
   * @param claim              parsed {@code org_rights} claim from the ID token
   * @param subject            the user's {@code sub} claim, used for logging
   * @param functionConstraint optional function identifier from the SSO {@code func} parameter;
   *                           when non-null, all returned data is filtered to that function only
   * @param orgConstraint      optional organization identifier from the SSO {@code org} parameter;
   *                           when non-null, the session is restricted to that single organization
   * @return populated session data
   */
  public @NonNull AdminSessionData bootstrap(
      final @NonNull OrgRightsClaim claim,
      final @NonNull String subject,
      final @Nullable String functionConstraint,
      final @Nullable String orgConstraint) {

    log.debug("Bootstrapping session data for user '{}' (superuser={}, functionConstraint={}, orgConstraint={})",
        subject, claim.superuser(), functionConstraint, orgConstraint);

    final List<FunctionInfo> allFunctions = this.keycloakAdminClient.fetchAllFunctions();

    List<FunctionInfo> functions;
    List<OrganizationInfo> organizations;
    List<UserInfo> users;

    Set<String> adminOrgIdentifiers;

    if (claim.superuser()) {
      functions = allFunctions;
      organizations = this.keycloakAdminClient.fetchAllOrganizationGroups();
      final List<UserInfo> allUsers = this.keycloakAdminClient.fetchAllUsers();
      final Map<String, List<UserRight>> rightsMap = this.fetchRightsForUsers(organizations);
      users = allUsers.stream()
          .map(u -> {
            final List<UserRight> rights = rightsMap.getOrDefault(u.userId(), List.of());
            return new UserInfo(u.userId(), u.username(), u.firstName(), u.lastName(),
                u.email(), u.personalIdentityNumber(), u.phoneNumber(), u.superuser(), List.copyOf(rights));
          })
          .toList();
      adminOrgIdentifiers = organizations.stream()
          .map(OrganizationInfo::orgIdentifier)
          .collect(Collectors.toSet());
    }
    else {
      final List<OrganizationInfo> allOrgs = this.keycloakAdminClient.fetchAllOrganizationGroups();
      adminOrgIdentifiers = claim.orgEntries().stream()
          .filter(e -> e.functions().stream().anyMatch(f -> "admin".equals(f.right())))
          .map(e -> e.orgIdentifier().getId())
          .collect(Collectors.toSet());
      final Set<String> adminOrgIds = adminOrgIdentifiers;
      organizations = allOrgs.stream()
          .filter(o -> adminOrgIds.contains(o.orgIdentifier()))
          .toList();
      // Apply org constraint early so that user fetching is scoped to the constrained org only
      if (orgConstraint != null) {
        organizations = organizations.stream()
            .filter(o -> orgConstraint.equals(o.orgIdentifier()))
            .toList();
      }
      functions = this.filterFunctionsForRegularAdmin(allFunctions, claim, organizations);
      users = this.fetchUsersForRegularAdmin(claim, organizations);
    }

    if (orgConstraint != null) {
      organizations = organizations.stream()
          .filter(o -> orgConstraint.equals(o.orgIdentifier()))
          .toList();
      adminOrgIdentifiers = adminOrgIdentifiers.stream()
          .filter(id -> orgConstraint.equals(id))
          .collect(Collectors.toSet());
    }

    if (functionConstraint != null) {
      functions = functions.stream()
          .filter(f -> functionConstraint.equals(f.id()))
          .toList();

      final String fc = functionConstraint;
      organizations = organizations.stream()
          .map(o -> o.withFilteredFunctions(f -> fc.equals(f)))
          .toList();

      users = users.stream()
          .map(u -> u.withFilteredRights(r -> r.functionId() == null || fc.equals(r.functionId())))
          .toList();
    }

    if (orgConstraint != null) {
      final String oc = orgConstraint;
      users = users.stream()
          .filter(u -> u.rights().stream().anyMatch(r -> oc.equals(r.orgIdentifier())))
          .map(u -> u.withFilteredRights(r -> oc.equals(r.orgIdentifier())))
          .toList();
    }

    this.logSummary(subject, claim.superuser(), functions, organizations, users);

    return new AdminSessionData(claim.superuser(), functionConstraint, orgConstraint, functions,
        organizations, users, adminOrgIdentifiers, claim);
  }

  // ---------------------------------------------------------------------------
  // Regular admin user fetching
  // ---------------------------------------------------------------------------

  /**
   * Returns the subset of {@code allFunctions} that the regular admin user has rights on.
   *
   * <p>For each organization entry in the claim, a wildcard function ({@code "*"}) grants
   * access to all functions attached to that organization; a named function grants access
   * to that specific function only.</p>
   */
  private @NonNull List<FunctionInfo> filterFunctionsForRegularAdmin(
      final @NonNull List<FunctionInfo> allFunctions,
      final @NonNull OrgRightsClaim claim,
      final @NonNull List<OrganizationInfo> allowedOrgs) {

    final Map<String, OrganizationInfo> orgMap = allowedOrgs.stream()
        .collect(Collectors.toMap(OrganizationInfo::orgIdentifier, o -> o));

    final Set<String> allowedFunctionIds = new LinkedHashSet<>();

    for (final OrgRightsClaim.OrgEntry orgEntry : claim.orgEntries()) {
      final OrganizationInfo orgInfo = orgMap.get(orgEntry.orgIdentifier().toString());
      if (orgInfo == null) {
        continue;
      }
      for (final OrgRightsClaim.FunctionEntry funcEntry : orgEntry.functions()) {
        if ("*".equals(funcEntry.function())) {
          allowedFunctionIds.addAll(orgInfo.attachedFunctions());
        }
        else {
          allowedFunctionIds.add(funcEntry.function());
        }
      }
    }

    return allFunctions.stream()
        .filter(f -> allowedFunctionIds.contains(f.id()))
        .toList();
  }

  private @NonNull List<UserInfo> fetchUsersForRegularAdmin(
      final @NonNull OrgRightsClaim claim,
      final @NonNull List<OrganizationInfo> relevantOrgs) {

    final Map<String, OrganizationInfo> orgMap = relevantOrgs.stream()
        .collect(Collectors.toMap(OrganizationInfo::orgIdentifier, o -> o));

    // Collect right groups with their org/function context
    final List<RightGroupContext> rightGroupContexts = new ArrayList<>();

    for (final OrgRightsClaim.OrgEntry orgEntry : claim.orgEntries()) {
      final OrganizationInfo orgInfo = orgMap.get(orgEntry.orgIdentifier().toString());
      if (orgInfo == null) {
        log.debug("Org '{}' from claim not found in Keycloak — skipping", orgEntry.orgIdentifier());
        continue;
      }

      final String orgIdentifier = orgEntry.orgIdentifier().toString();
      final List<Map<String, Object>> orgChildren =
          this.keycloakAdminClient.fetchGroupChildren(orgInfo.groupId());

      final List<Map<String, Object>> orgLevelRightGroups = orgChildren.stream()
          .filter(c -> isRightGroup(getString(c, "name")))
          .toList();
      final List<Map<String, Object>> funcSubGroups = orgChildren.stream()
          .filter(c -> !isRightGroup(getString(c, "name")))
          .toList();

      for (final OrgRightsClaim.FunctionEntry funcEntry : orgEntry.functions()) {
        if ("*".equals(funcEntry.function())) {
          // Org-wide right: collect org-level right groups
          for (final Map<String, Object> rg : orgLevelRightGroups) {
            addContext(rightGroupContexts, rg, orgIdentifier, null);
          }
          // Collect all function-level right groups
          for (final Map<String, Object> funcSubGroup : funcSubGroups) {
            final String funcId = getString(funcSubGroup, "name");
            this.keycloakAdminClient.fetchGroupChildren(getString(funcSubGroup, "id"))
                .stream()
                .filter(c -> isRightGroup(getString(c, "name")))
                .forEach(c -> addContext(rightGroupContexts, c, orgIdentifier, funcId));
          }
        }
        else {
          // Specific function: always collect org-level right groups first
          for (final Map<String, Object> rg : orgLevelRightGroups) {
            addContext(rightGroupContexts, rg, orgIdentifier, null);
          }
          // Then collect only that specific function's right groups
          funcSubGroups.stream()
              .filter(g -> funcEntry.function().equals(getString(g, "name")))
              .findFirst()
              .ifPresentOrElse(
                  funcSubGroup -> {
                    final String funcId = getString(funcSubGroup, "name");
                    this.keycloakAdminClient.fetchGroupChildren(getString(funcSubGroup, "id"))
                        .stream()
                        .filter(c -> isRightGroup(getString(c, "name")))
                        .forEach(c -> addContext(rightGroupContexts, c, orgIdentifier, funcId));
                  },
                  () -> log.debug("Function sub-group '{}' not found under org '{}'",
                      funcEntry.function(), orgIdentifier));
        }
      }
    }

    // Deduplicate by groupId to avoid fetching the same group members multiple times
    // (org-level right groups may be added once per function entry for the same org)
    final List<RightGroupContext> deduplicated = rightGroupContexts.stream()
        .collect(Collectors.toMap(
            RightGroupContext::groupId,
            c -> c,
            (a, b) -> a,
            LinkedHashMap::new))
        .values()
        .stream()
        .toList();

    // Fetch members to determine which users are visible (profileMap)
    final Map<String, UserInfo> profileMap = new LinkedHashMap<>();
    for (final RightGroupContext ctx : deduplicated) {
      for (final UserInfo user : this.keycloakAdminClient.fetchGroupMembers(ctx.groupId())) {
        profileMap.putIfAbsent(user.userId(), user);
      }
    }

    // Get complete rights for all visible users across all relevant orgs
    final Map<String, List<UserRight>> rightsMap = this.fetchRightsForUsers(relevantOrgs);

    // Merge profile + rights into final UserInfo list
    return profileMap.values().stream()
        .map(u -> {
          final List<UserRight> rights = rightsMap.getOrDefault(u.userId(), List.of());
          return new UserInfo(u.userId(), u.username(), u.firstName(), u.lastName(),
              u.email(), u.personalIdentityNumber(), u.phoneNumber(), u.superuser(), List.copyOf(rights));
        })
        .toList();
  }

  /**
   * Traverses all org groups and their function sub-groups to build a complete map of
   * userId → rights. Used by both the superuser path (all orgs) and the regular-admin path
   * (scoped orgs).
   */
  private @NonNull Map<String, List<UserRight>> fetchRightsForUsers(
      final @NonNull List<OrganizationInfo> orgs) {

    final Map<String, List<UserRight>> rightsMap = new LinkedHashMap<>();

    for (final OrganizationInfo orgInfo : orgs) {
      final String orgIdentifier = orgInfo.orgIdentifier();
      final List<Map<String, Object>> orgChildren =
          this.keycloakAdminClient.fetchGroupChildren(orgInfo.groupId());

      // Org-level right groups (_admin, _write, _read directly under org)
      for (final Map<String, Object> child : orgChildren) {
        final String name = getString(child, "name");
        if (isRightGroup(name)) {
          final String groupId = getString(child, "id");
          if (!groupId.isEmpty()) {
            final String right = rightFromGroupName(name);
            for (final UserInfo user : this.keycloakAdminClient.fetchGroupMembers(groupId)) {
              rightsMap.computeIfAbsent(user.userId(), k -> new ArrayList<>())
                  .add(new UserRight(orgIdentifier, null, right));
            }
          }
        }
      }

      // Function sub-groups and their right groups
      for (final Map<String, Object> child : orgChildren) {
        final String name = getString(child, "name");
        if (!isRightGroup(name)) {
          final String funcGroupId = getString(child, "id");
          for (final Map<String, Object> funcChild :
              this.keycloakAdminClient.fetchGroupChildren(funcGroupId)) {
            final String fcName = getString(funcChild, "name");
            if (isRightGroup(fcName)) {
              final String groupId = getString(funcChild, "id");
              if (!groupId.isEmpty()) {
                final String right = rightFromGroupName(fcName);
                for (final UserInfo user : this.keycloakAdminClient.fetchGroupMembers(groupId)) {
                  rightsMap.computeIfAbsent(user.userId(), k -> new ArrayList<>())
                      .add(new UserRight(orgIdentifier, name, right));
                }
              }
            }
          }
        }
      }
    }

    return rightsMap;
  }

  /**
   * Context record capturing everything needed to derive a {@link UserRight} from a right group.
   */
  private record RightGroupContext(
      @NonNull String groupId,
      @NonNull String orgIdentifier,
      @Nullable String functionId,
      @NonNull String right) {
  }

  // ---------------------------------------------------------------------------
  // Logging
  // ---------------------------------------------------------------------------

  private void logSummary(
      final @NonNull String subject,
      final boolean superuser,
      final @NonNull List<FunctionInfo> functions,
      final @NonNull List<OrganizationInfo> organizations,
      final @NonNull List<UserInfo> users) {

    log.info("Bootstrap complete for user '{}' (superuser={})", subject, superuser);
    log.info("  Functions  : {} total — {}", functions.size(),
        functions.stream().map(FunctionInfo::id).collect(Collectors.joining(", ")));
    log.info("  Orgs       : {} total — {}", organizations.size(),
        organizations.stream()
            .map(o -> o.orgIdentifier() + " (" + o + ")")
            .collect(Collectors.joining(", ")));
    log.info("  Users      : {} total", users.size());

    if (log.isDebugEnabled()) {
      for (final FunctionInfo f : functions) {
        log.debug("  Function detail: {}", this.toJson(f));
      }
      for (final OrganizationInfo o : organizations) {
        log.debug("  Org detail: {}", this.toJson(o));
      }
      for (final UserInfo u : users) {
        log.debug("  User detail: {}", this.toJson(redactUser(u)));
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private static boolean isRightGroup(final @NonNull String name) {
    return "_admin".equals(name) || "_write".equals(name) || "_read".equals(name);
  }

  private static String rightFromGroupName(final @NonNull String name) {
    return switch (name) {
      case "_admin" -> "admin";
      case "_write" -> "write";
      default -> "read";
    };
  }

  private static void addContext(
      final @NonNull List<RightGroupContext> contexts,
      final @NonNull Map<String, Object> group,
      final @NonNull String orgIdentifier,
      final @Nullable String functionId) {
    final String id = getString(group, "id");
    final String name = getString(group, "name");
    if (!id.isEmpty()) {
      contexts.add(new RightGroupContext(id, orgIdentifier, functionId, rightFromGroupName(name)));
    }
  }

  private static UserInfo redactUser(final @NonNull UserInfo user) {
    final String pin = user.personalIdentityNumber();
    final String redacted = (pin != null && pin.length() > 8)
        ? pin.substring(0, 8) + "****"
        : pin;
    return new UserInfo(
        user.userId(), user.username(), user.firstName(),
        user.lastName(), user.email(), redacted, user.phoneNumber(), user.superuser(), user.rights());
  }

  private String toJson(final Object obj) {
    try {
      return this.objectMapper.writeValueAsString(obj);
    }
    catch (final Exception e) {
      return obj.toString();
    }
  }

  private static @NonNull String getString(
      final @NonNull Map<String, Object> map,
      final @NonNull String key) {
    return map.get(key) instanceof final String s ? s : "";
  }

}
