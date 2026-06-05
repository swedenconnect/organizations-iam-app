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

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.iam.commons.types.OrganizationID;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses the {@code org_rights} OIDC claim and derives Spring Security {@link GrantedAuthority} objects.
 *
 * <p>The {@code org_rights} claim is a JSON array produced by the KeyCloak protocol mapper.
 * Each entry is either a superuser marker ({@code {"superuser": true}}) or an organization object with an
 * {@code organization_identifier} and a {@code functions} array.</p>
 *
 * <p>Authorities follow the pattern {@code {org_identifier}:{function}:{right}}, e.g.
 * {@code 5590026042:walletreg:admin} or {@code 5590026042:*:admin} for org-wide rights,
 * represented as {@link OrganizationalAuthority} instances.</p>
 *
 * <p>Superusers receive the single authority {@code ROLE_SUPERUSER}.</p>
 *
 * @author Martin Lindström
 */
@Slf4j
public class OrgRightsClaimParser {

  /**
   * Parses the raw {@code org_rights} claim value into a structured {@link OrgRightsClaim}.
   *
   * @param rawClaim the value of the {@code org_rights} claim, typically a {@code List<Map>}
   * @return a parsed claim; never {@code null}, but may represent an empty/absent claim
   */
  public @NonNull OrgRightsClaim parse(final @Nullable Object rawClaim) {
    if (!(rawClaim instanceof final List<?> list) || list.isEmpty()) {
      log.debug("[org-rights-parser] org_rights claim is absent or empty — returning empty claim");
      return new OrgRightsClaim(false, List.of());
    }
    log.debug("[org-rights-parser] Parsing org_rights claim with {} item(s)", list.size());

    // First pass: check for superuser
    for (final Object item : list) {
      if (item instanceof final Map<?, ?> map && Boolean.TRUE.equals(map.get("superuser"))) {
        log.debug("[org-rights-parser] Found superuser marker — returning superuser claim");
        return new OrgRightsClaim(true, List.of());
      }
    }

    // Second pass: parse organization entries
    final List<OrgRightsClaim.OrgEntry> entries = new ArrayList<>();
    for (final Object item : list) {
      if (!(item instanceof final Map<?, ?> map)) {
        log.debug("[org-rights-parser] Skipping non-map item in org_rights list");
        continue;
      }

      final String orgIdStr = (String) map.get("organization_identifier");
      if (orgIdStr == null) {
        log.debug("[org-rights-parser] Skipping entry without organization_identifier");
        continue;
      }

      final OrganizationID orgId;
      try {
        orgId = OrganizationID.of(orgIdStr);
      }
      catch (final IllegalArgumentException e) {
        log.debug("[org-rights-parser] Skipping entry with invalid organization_identifier '{}': {}",
            orgIdStr, e.getMessage());
        continue;
      }

      final LocalizedString name = new LocalizedString();
      for (final Map.Entry<?, ?> entry : map.entrySet()) {
        final String key = entry.getKey() instanceof String k ? k : null;
        final String val = entry.getValue() instanceof String v ? v : null;
        if (key != null && val != null && key.startsWith("organization_name")) {
          name.addFromClaim(key, val);
        }
      }

      final List<OrgRightsClaim.FunctionEntry> functions = new ArrayList<>();
      if (map.get("functions") instanceof final List<?> funcs) {
        for (final Object f : funcs) {
          if (f instanceof final Map<?, ?> funcMap) {
            final String func = (String) funcMap.get("function");
            final String right = (String) funcMap.get("right");
            if (func != null && right != null) {
              log.debug("[org-rights-parser]   org '{}' — function '{}', right '{}'", orgIdStr, func, right);
              functions.add(new OrgRightsClaim.FunctionEntry(func, right));
            }
            else {
              log.debug("[org-rights-parser]   org '{}' — skipping function entry with missing func or right", orgIdStr);
            }
          }
        }
      }

      log.debug("[org-rights-parser] Parsed org entry '{}' with {} function(s)", orgIdStr, functions.size());
      entries.add(new OrgRightsClaim.OrgEntry(orgId, name, List.copyOf(functions)));
    }

    log.debug("[org-rights-parser] Finished parsing — {} org entr{} produced",
        entries.size(), entries.size() == 1 ? "y" : "ies");
    return new OrgRightsClaim(false, List.copyOf(entries));
  }

  /**
   * Checks whether the claim satisfies the admin right requirement, optionally constrained to a specific organization
   * and/or function. Returns normally if the constraint is satisfied.
   *
   * <p>Superusers always satisfy the constraint. For regular users, at least one function entry
   * within the (possibly constrained) organization set must carry {@code right=admin}.</p>
   *
   * @param claim the parsed claim
   * @param orgConstraint the required organization identifier, or {@code null} for no constraint
   * @param funcConstraint the required function identifier, or {@code null} for no constraint
   * @throws InsufficientRightsException if the constraint is not satisfied
   */
  public void checkAdminConstraint(
      final @NonNull OrgRightsClaim claim,
      final @Nullable String orgConstraint,
      final @Nullable String funcConstraint) throws InsufficientRightsException {

    log.debug("[org-rights-parser] checkAdminConstraint: org='{}', function='{}'", orgConstraint, funcConstraint);

    if (claim.superuser()) {
      log.debug("[org-rights-parser] Superuser — admin constraint satisfied");
      return;
    }

    List<OrgRightsClaim.OrgEntry> orgs = claim.orgEntries();

    if (orgConstraint != null) {
      orgs = orgs.stream()
          .filter(e -> orgConstraint.equals(e.orgIdentifier().toString()))
          .toList();
      if (orgs.isEmpty()) {
        log.debug("[org-rights-parser] No rights entry found for organization '{}'", orgConstraint);
        throw new InsufficientRightsException("No rights for organization " + orgConstraint);
      }
    }

    if (orgs.isEmpty()) {
      log.debug("[org-rights-parser] Claim has no org entries");
      throw new InsufficientRightsException("User has no organizational rights");
    }

    for (final OrgRightsClaim.OrgEntry org : orgs) {
      List<OrgRightsClaim.FunctionEntry> funcs = org.functions();
      if (funcConstraint != null) {
        funcs = funcs.stream()
            .filter(f -> "*".equals(f.function()) || funcConstraint.equals(f.function()))
            .toList();
      }
      for (final OrgRightsClaim.FunctionEntry f : funcs) {
        if ("admin".equals(f.right())) {
          log.debug("[org-rights-parser] Admin right found for org '{}', function '{}' — constraint satisfied",
              org.orgIdentifier(), f.function());
          return;
        }
      }
    }

    if (orgConstraint != null && funcConstraint != null) {
      throw new InsufficientRightsException(
          "No admin right for function '" + funcConstraint + "' in organization " + orgConstraint);
    }
    if (orgConstraint != null) {
      throw new InsufficientRightsException("No admin right for organization " + orgConstraint);
    }
    if (funcConstraint != null) {
      throw new InsufficientRightsException("No admin right for function '" + funcConstraint + "'");
    }
    throw new InsufficientRightsException("No admin rights found in any organization");
  }

  /**
   * Builds a {@link GrantedAuthority} list in function-scoped mode.
   *
   * <p>In function-scoped mode the application is configured for a single function. The
   * {@code org_rights} claim is filtered to entries relevant to {@code functionId} — both
   * entries with an exact function match and entries with the org-wide ({@code *}) wildcard.
   * For each organization the highest effective right across all matching entries is resolved
   * ({@code admin} &gt; {@code write} &gt; {@code read}), and a single
   * {@link FunctionScopedAuthority} of the form {@code {orgId}:{right}} is produced per
   * organization that has at least one matching entry.</p>
   *
   * <p>Superusers receive the single authority {@code ROLE_SUPERUSER} regardless of mode.</p>
   *
   * @param claim the parsed claim; must not be null
   * @param functionId the function identifier this application is scoped to; must not be null
   * @return the effective granted authorities; never null
   */
  public @NonNull List<GrantedAuthority> buildFunctionScopedAuthorities(
      final @NonNull OrgRightsClaim claim,
      final @NonNull String functionId) {

    log.debug("[org-rights-parser] buildFunctionScopedAuthorities: function='{}'", functionId);

    if (claim.superuser()) {
      log.debug("[org-rights-parser] Superuser — issuing ROLE_SUPERUSER");
      return List.of(new SimpleGrantedAuthority("ROLE_SUPERUSER"));
    }

    final List<GrantedAuthority> authorities = new ArrayList<>();
    for (final OrgRightsClaim.OrgEntry org : claim.orgEntries()) {
      final Optional<OrganizationRight> highestRight = org.functions().stream()
          .filter(f -> "*".equals(f.function()) || functionId.equals(f.function()))
          .map(f -> {
            try {
              return OrganizationRight.parse(f.right());
            }
            catch (final IllegalArgumentException e) {
              log.debug("[org-rights-parser] Skipping unrecognized right '{}' for org '{}', function '{}'",
                  f.right(), org.orgIdentifier(), f.function());
              return null;
            }
          })
          .filter(r -> r != null)
          .max(Comparator.naturalOrder());

      if (highestRight.isPresent()) {
        log.debug("[org-rights-parser] org '{}' — highest effective right for function '{}': {}",
            org.orgIdentifier(), functionId, highestRight.get());
        authorities.add(FunctionScopedAuthority.of(org.orgIdentifier(), highestRight.get()));
      }
      else {
        log.debug("[org-rights-parser] org '{}' — no matching entries for function '{}', skipping",
            org.orgIdentifier(), functionId);
      }
    }

    log.debug("[org-rights-parser] Resolved {} function-scoped authorit{}", authorities.size(),
        authorities.size() == 1 ? "y" : "ies");
    return List.copyOf(authorities);
  }

  /**
   * Builds a {@link GrantedAuthority} list from the claim, restricted to the given constraints.
   *
   * <p>When {@code orgConstraint} is set, only entries for that organization are included.
   * When {@code funcConstraint} is set, only entries for that function (or {@code *}) are included. Both may be
   * {@code null} to include all effective rights.</p>
   *
   * <p>For regular users the authorities are {@link OrganizationalAuthority} instances. For superusers
   * the single authority {@code ROLE_SUPERUSER} is returned as a {@link SimpleGrantedAuthority}.</p>
   *
   * @param claim the parsed claim
   * @param orgConstraint the organization to restrict to, or {@code null}
   * @param funcConstraint the function to restrict to, or {@code null}
   * @return the effective granted authorities; never {@code null}
   */
  public @NonNull List<GrantedAuthority> buildAuthorities(
      final @NonNull OrgRightsClaim claim,
      final @Nullable String orgConstraint,
      final @Nullable String funcConstraint) {

    log.debug("[org-rights-parser] buildAuthorities: org='{}', function='{}'", orgConstraint, funcConstraint);

    if (claim.superuser()) {
      log.debug("[org-rights-parser] Superuser — issuing ROLE_SUPERUSER");
      return List.of(new SimpleGrantedAuthority("ROLE_SUPERUSER"));
    }

    List<OrgRightsClaim.OrgEntry> orgs = claim.orgEntries();
    if (orgConstraint != null) {
      orgs = orgs.stream()
          .filter(e -> orgConstraint.equals(e.orgIdentifier().toString()))
          .toList();
      log.debug("[org-rights-parser] Filtered to org '{}': {} entr{} remaining",
          orgConstraint, orgs.size(), orgs.size() == 1 ? "y" : "ies");
    }

    final List<GrantedAuthority> authorities = new ArrayList<>();
    for (final OrgRightsClaim.OrgEntry org : orgs) {
      List<OrgRightsClaim.FunctionEntry> funcs = org.functions();
      if (funcConstraint != null) {
        funcs = funcs.stream()
            .filter(f -> "*".equals(f.function()) || funcConstraint.equals(f.function()))
            .toList();
        log.debug("[org-rights-parser] org '{}' — {} function entr{} after filtering for '{}'",
            org.orgIdentifier(), funcs.size(), funcs.size() == 1 ? "y" : "ies", funcConstraint);
      }
      for (final OrgRightsClaim.FunctionEntry f : funcs) {
        try {
          final OrganizationalAuthority authority = OrganizationalAuthority.of(
              org.orgIdentifier(), f.function(), OrganizationRight.parse(f.right()));
          log.debug("[org-rights-parser] Adding authority: {}", authority.getAuthority());
          authorities.add(authority);
        }
        catch (final IllegalArgumentException e) {
          log.debug("[org-rights-parser] Skipping unrecognized right '{}' for org '{}', function '{}'",
              f.right(), org.orgIdentifier(), f.function());
        }
      }
    }

    log.debug("[org-rights-parser] Resolved {} authorit{}", authorities.size(),
        authorities.size() == 1 ? "y" : "ies");
    return List.copyOf(authorities);
  }

}
