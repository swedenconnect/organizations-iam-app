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
package se.swedenconnect.iam.admin.keycloak.model;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;

import java.util.List;
import java.util.Set;

/**
 * Bootstrapped session data for an authenticated admin user.
 *
 * <p>Loaded after successful login via {@link se.swedenconnect.iam.admin.keycloak.AdminDataBootstrapService}
 * and stored in the HTTP session. Organizations and users are NOT cached here — they are
 * fetched on-demand via the respective paginated API endpoints.</p>
 *
 * <p>When {@code functionConstraint} is non-null, the session was initiated via the SSO login
 * path with a {@code func} parameter. API responses are filtered to reflect only that function.</p>
 *
 * <p>When {@code orgConstraint} is non-null, the session was initiated via the SSO login path
 * with an {@code org} parameter. API responses are restricted to that single organization.</p>
 *
 * @author Martin Lindström
 */
public record AdminSessionData(
    boolean currentUserIsSuperuser,
    @Nullable String functionConstraint,
    @Nullable String orgConstraint,
    @NonNull List<FunctionInfo> functions,
    @NonNull Set<String> adminOrgIdentifiers,
    @NonNull OrgRightsClaim claim) {

  /**
   * Decides whether a right held by some user may be disclosed to this caller.
   *
   * <p>A superuser sees every right. For anyone else a right is visible when it is held in an
   * organization the caller administers, and it is either granted at the organization level or
   * granted on a function the caller administers in that organization. An organization-level right
   * covers every function of the organization, so a caller who administers a single function there
   * is still entitled to see it; the rights on the <em>other</em> functions of that organization
   * are not theirs to see.</p>
   *
   * <p>Any organization or function constraint carried by the session narrows this further.</p>
   *
   * @param right the right to test
   * @return {@code true} if the right may be disclosed to the caller
   */
  public boolean mayViewRight(final @NonNull UserRight right) {
    if (this.currentUserIsSuperuser) {
      return true;
    }
    if (!this.adminOrgIdentifiers.contains(right.orgIdentifier())) {
      return false;
    }
    final String functionId = right.functionId();
    if (functionId != null
        && !this.hasOrgLevelAdminRight(right.orgIdentifier())
        && !this.hasFunctionAdminRight(right.orgIdentifier(), functionId)) {
      return false;
    }
    if (this.orgConstraint != null && !this.orgConstraint.equals(right.orgIdentifier())) {
      return false;
    }
    return this.functionConstraint == null
        || functionId == null
        || this.functionConstraint.equals(functionId);
  }

  /**
   * Decides whether a user may be addressed by this caller at all, that is whether the user holds
   * at least one right the caller is entitled to see. A caller who may not address a user must be
   * answered as if the user did not exist.
   *
   * <p>The rights must be the user's live rights, not a snapshot of who was visible earlier: a
   * right granted moments ago has to make the user addressable in the same interaction.</p>
   *
   * @param userRights the user's rights, as held in Keycloak right now
   * @return {@code true} if at least one of the rights is visible to the caller
   */
  public boolean mayViewUser(final @NonNull List<UserRight> userRights) {
    // A superuser may address every user, including one holding no rights at all.
    return this.currentUserIsSuperuser || userRights.stream().anyMatch(this::mayViewRight);
  }

  /**
   * True if the caller holds {@code admin} at the <em>organization</em> level for the given
   * organization. This is the one legitimate use of {@code org_level_right}: it answers "may this
   * person administer the organization itself", not "what may they do".
   *
   * @param orgIdentifier the organization identifier
   * @return {@code true} if the caller holds the org-level admin right
   */
  public boolean hasOrgLevelAdminRight(final @NonNull String orgIdentifier) {
    return this.claim.orgEntries().stream()
        .filter(e -> orgIdentifier.equals(e.orgIdentifier().toString()))
        .anyMatch(e -> "admin".equals(e.orgLevelRight()));
  }

  /**
   * True if the caller holds {@code admin} on the given function within the given organization.
   *
   * @param orgIdentifier the organization identifier
   * @param functionId    the function identifier
   * @return {@code true} if the caller holds the function-level admin right
   */
  public boolean hasFunctionAdminRight(
      final @NonNull String orgIdentifier, final @NonNull String functionId) {
    return this.claim.orgEntries().stream()
        .filter(e -> orgIdentifier.equals(e.orgIdentifier().toString()))
        .flatMap(e -> e.functions().stream())
        .anyMatch(f -> functionId.equals(f.function()) && "admin".equals(f.right()));
  }

}
