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

/**
 * The outcome of processing a single entry from an {@link ImportExportBundle}.
 *
 * <p>{@code key} identifies the entry for display (a function id, an org identifier, or a
 * user's {@code personalIdentityNumber}/{@code orgAffiliation}). {@code status} is one of
 * {@code new}, {@code skipped_duplicate} or {@code error} in a dry-run report, and one of
 * {@code created}, {@code skipped_duplicate} or {@code error} in a final import report.
 * {@code reason} is set for {@code skipped_duplicate} and {@code error}.</p>
 *
 * @author PF Plars
 */
public record ImportItemOutcome(
    @NonNull String key,
    @NonNull String status,
    @Nullable String reason) {
}
