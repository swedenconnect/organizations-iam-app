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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.FunctionInfo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates the KeyCloak Admin REST API calls required to populate an
 * {@link AdminSessionData} after a successful OIDC login.
 *
 * <p>Organizations and users are NOT loaded at login time. They are fetched on-demand
 * via the paginated REST API. Only the function list and the caller's admin org identifiers
 * (derived from the {@code org_rights} claim) are stored in the session.</p>
 *
 * @author Martin Lindström
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AdminDataBootstrapService {

  private final KeycloakAdminClient keycloakAdminClient;

  /**
   * Fetches and returns the minimal session data needed for the authenticated user.
   *
   * @param claim              parsed {@code org_rights} claim from the ID token
   * @param subject            the user's {@code sub} claim, used for logging
   * @param functionConstraint optional function identifier from the SSO {@code func} parameter
   * @param orgConstraint      optional organization identifier from the SSO {@code org} parameter
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
    Set<String> adminOrgIdentifiers;

    if (claim.superuser()) {
      functions = allFunctions;
      adminOrgIdentifiers = Set.of();
    }
    else {
      // Derive org identifiers where the caller has admin rights — either on a function within the
      // organization, or at the organization level (which also covers an organization that has no
      // functions attached, and therefore no function entries to match on).
      adminOrgIdentifiers = claim.orgEntries().stream()
          .filter(e -> "admin".equals(e.orgLevelRight())
              || e.functions().stream().anyMatch(f -> "admin".equals(f.right())))
          .map(e -> e.orgIdentifier().getId())
          .collect(Collectors.toCollection(LinkedHashSet::new));

      if (orgConstraint != null) {
        final String oc = orgConstraint;
        adminOrgIdentifiers = adminOrgIdentifiers.stream()
            .filter(id -> oc.equals(id))
            .collect(Collectors.toCollection(LinkedHashSet::new));
      }

      functions = filterFunctionsForRegularAdmin(allFunctions, claim, adminOrgIdentifiers);
    }

    if (functionConstraint != null) {
      final String fc = functionConstraint;
      functions = functions.stream().filter(f -> fc.equals(f.id())).toList();
    }

    this.logSummary(subject, claim.superuser(), functions, adminOrgIdentifiers);

    return new AdminSessionData(claim.superuser(), functionConstraint, orgConstraint,
        functions, Set.copyOf(adminOrgIdentifiers), claim);
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  /**
   * Returns the subset of {@code allFunctions} that the regular admin user has rights on.
   *
   * <p>The claim's function entries are authoritative: the protocol mapper has already expanded any
   * organization-level right into one entry per function attached to that organization, so no
   * organization details need to be loaded here.</p>
   *
   * @param allFunctions all functions defined in the realm
   * @param claim the parsed {@code org_rights} claim
   * @param allowedOrgIdentifiers the organizations to consider, i.e. those the caller administers
   * @return the functions the caller has some right on; never {@code null}
   */
  private @NonNull List<FunctionInfo> filterFunctionsForRegularAdmin(
      final @NonNull List<FunctionInfo> allFunctions,
      final @NonNull OrgRightsClaim claim,
      final @NonNull Set<String> allowedOrgIdentifiers) {

    final Set<String> allowedFunctionIds = new LinkedHashSet<>();

    for (final OrgRightsClaim.OrgEntry orgEntry : claim.orgEntries()) {
      if (!allowedOrgIdentifiers.contains(orgEntry.orgIdentifier().toString())) {
        continue;
      }
      for (final OrgRightsClaim.FunctionEntry funcEntry : orgEntry.functions()) {
        allowedFunctionIds.add(funcEntry.function());
      }
    }

    return allFunctions.stream()
        .filter(f -> allowedFunctionIds.contains(f.id()))
        .toList();
  }

  private void logSummary(
      final @NonNull String subject,
      final boolean superuser,
      final @NonNull List<FunctionInfo> functions,
      final @NonNull Set<String> adminOrgIdentifiers) {

    log.info("Bootstrap complete for user '{}' (superuser={})", subject, superuser);
    log.info("  Functions         : {} total — {}", functions.size(),
        functions.stream().map(FunctionInfo::id).collect(Collectors.joining(", ")));
    if (!superuser) {
      log.info("  Admin org scope   : {} — {}", adminOrgIdentifiers.size(),
          String.join(", ", adminOrgIdentifiers));
    }
  }

}