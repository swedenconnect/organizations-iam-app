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
 * and stored in the HTTP session. The scope of the data reflects the user's rights:
 * superusers receive everything; regular admins receive only the organizations and users
 * within their authorized scope.</p>
 *
 * <p>When {@code functionConstraint} is non-null, the session was initiated via the SSO login
 * path with a {@code func} parameter. All session data (functions, attached functions on
 * organizations, and user rights) is already filtered to reflect only that function.</p>
 *
 * <p>When {@code orgConstraint} is non-null, the session was initiated via the SSO login path
 * with an {@code org} parameter. The session data is restricted to that single organization.</p>
 *
 * @author Martin Lindström
 */
public record AdminSessionData(
    boolean currentUserIsSuperuser,
    @Nullable String functionConstraint,
    @Nullable String orgConstraint,
    @NonNull List<FunctionInfo> functions,
    @NonNull List<OrganizationInfo> organizations,
    @NonNull List<UserInfo> users,
    @NonNull Set<String> adminOrgIdentifiers,
    @NonNull OrgRightsClaim claim) {
}
