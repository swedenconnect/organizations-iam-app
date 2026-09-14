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
 * A single person inside an {@link ImportExportBundle}, with their organizational rights.
 *
 * <p>{@code personalIdentityNumber} and {@code orgAffiliation} are the identifiers a duplicate is
 * detected on — the same eID attributes the application already enforces uniqueness on when a
 * user is created through the regular form. At least one of them must be given. Superuser status
 * is deliberately not represented here: it cannot be granted through user creation and export
 * excludes superuser accounts entirely.</p>
 *
 * @author PF Plars
 */
public record BundleUserEntry(
    @NonNull String name,
    @Nullable String email,
    @Nullable String personalIdentityNumber,
    @Nullable String orgAffiliation,
    @Nullable String phoneNumber,
    @NonNull List<BundleUserRightEntry> rights) {
}
