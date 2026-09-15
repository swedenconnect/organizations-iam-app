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
package se.swedenconnect.iam.admin.controllers.dto.impexp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A single person inside an {@link ImportExportBundle}, with their organizational rights.
 *
 * <p>{@code personal_identity_number} and {@code org_affiliation} are the identifiers a duplicate
 * is detected on, the same eID attributes the application already enforces uniqueness on when a
 * user is created through the regular form. At least one of them must be given. Superuser status
 * is deliberately not represented here: it cannot be granted through user creation and export
 * excludes superuser accounts entirely.</p>
 *
 * <p>{@code username} is the Keycloak username. An export always carries it. On import it is
 * optional, and honoured only when the deployment allows an administrator to choose the user ID
 * ({@code iam.admin.user-registration.allow-select-user-id}). It is not a duplicate key.</p>
 *
 * @author PF Plars
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BundleUserEntry(
    @JsonProperty("name") @NonNull String name,
    @JsonProperty("username") @Nullable String username,
    @JsonProperty("email") @Nullable String email,
    @JsonProperty("personal_identity_number") @Nullable String personalIdentityNumber,
    @JsonProperty("org_affiliation") @Nullable String orgAffiliation,
    @JsonProperty("phone_number") @Nullable String phoneNumber,
    @JsonProperty("rights") @NonNull List<BundleUserRightEntry> rights) {
}
