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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Root JSON structure for both export and import of organizations, functions and users.
 *
 * <p>Processing order on import is {@code functions} then {@code organizations} then
 * {@code users} — later entries reference earlier ones by identifier
 * ({@code attachedFunctions} refers to a function {@code id}; a user right refers to an
 * {@code orgIdentifier} and, optionally, a {@code functionId}). Any of the three lists may be
 * empty; an import file need not cover every entity type.</p>
 *
 * @author PF Plars
 */
public record ImportExportBundle(
    @NonNull String schemaVersion,
    @Nullable String exportedAt,
    @NonNull List<BundleFunctionEntry> functions,
    @NonNull List<BundleOrganizationEntry> organizations,
    @NonNull List<BundleUserEntry> users) {

  /** The only {@code schemaVersion} accepted on import in this version of the application. */
  public static final String SCHEMA_VERSION = "1.0";
}
