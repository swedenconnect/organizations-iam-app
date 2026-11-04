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
 * A right held by a user, as read from the right groups of an organization.
 *
 * @param userId the Keycloak user ID
 * @param displayName the user's name, or {@code null} if the user has none
 * @param orgIdentifier the organization identifier
 * @param functionId the function identifier, or {@code null} for an organization-wide right
 * @param right the right level ({@code read}, {@code write} or {@code admin})
 * @author Martin Lindström
 */
public record RightHolder(
    @NonNull String userId,
    @Nullable String displayName,
    @NonNull String orgIdentifier,
    @Nullable String functionId,
    @NonNull String right) {
}
