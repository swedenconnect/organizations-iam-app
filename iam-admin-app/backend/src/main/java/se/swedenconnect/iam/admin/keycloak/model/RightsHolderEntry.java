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

/**
 * A user holding a right on a specific (organization, function) combination.
 *
 * <p>Used as the intermediate type returned by
 * {@link se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient#fetchFunctionRightsHolders}.</p>
 *
 * @param userId                  Keycloak user UUID
 * @param personalIdentityNumber  the user's personalIdentityNumber attribute, or {@code null}
 * @param name                    display name (firstName + lastName, or username fallback)
 * @param right                   effective right: {@code admin}, {@code write}, or {@code read}
 * @param scope                   {@code "function"} or {@code "organization"}
 * @author Martin Lindström
 */
public record RightsHolderEntry(
    @NonNull String userId,
    @Nullable String personalIdentityNumber,
    @Nullable String name,
    @NonNull String right,
    @NonNull String scope) {
}
