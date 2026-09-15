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
 * Root JSON structure for both export and import of organizations, functions and users.
 *
 * <p>Every key in the bundle is snake_case, and a localized value is carried as one key per
 * language tagged after a {@code #}, matching the Keycloak group attributes the values are read
 * from and written to.</p>
 *
 * <p>Processing order on import is {@code functions} then {@code organizations} then
 * {@code users}, and later entries reference earlier ones by identifier
 * ({@code attached_functions} refers to a function {@code id}; a user right refers to an
 * {@code org_identifier} and, optionally, a {@code function_id}). Any of the three lists may be
 * empty; an import file need not cover every entity type.</p>
 *
 * @author PF Plars
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImportExportBundle(
    @JsonProperty("schema_version") @NonNull String schemaVersion,
    @JsonProperty("exported_at") @Nullable String exportedAt,
    @JsonProperty("functions") @NonNull List<BundleFunctionEntry> functions,
    @JsonProperty("organizations") @NonNull List<BundleOrganizationEntry> organizations,
    @JsonProperty("users") @NonNull List<BundleUserEntry> users) {

  /** The only {@code schema_version} accepted on import in this version of the application. */
  public static final String SCHEMA_VERSION = "1.0";
}
