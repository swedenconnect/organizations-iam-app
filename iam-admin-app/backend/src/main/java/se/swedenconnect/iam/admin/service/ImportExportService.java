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
package se.swedenconnect.iam.admin.service;

import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.NonNull;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportExportBundle;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportPreviewReport;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportReport;

/**
 * Service backing the superuser-only export/import feature: a full-realm JSON snapshot of
 * functions, organizations and users, and a two-step (dry-run, then confirm) bulk import of the
 * same shape.
 *
 * <p>Callers of every method here have already been checked for superuser status —
 * {@link se.swedenconnect.iam.admin.controllers.ImportExportController} is the only caller and
 * enforces that before delegating.</p>
 *
 * @author PF Plars
 */
public interface ImportExportService {

  /**
   * Builds a full-realm snapshot: every function, every organization with its attached
   * functions, and every non-superuser user with their organizational rights. Superuser accounts
   * are deliberately excluded — see {@link se.swedenconnect.iam.admin.controllers.dto.impexp.BundleUserEntry}.
   *
   * @return the export bundle
   */
  @NonNull ImportExportBundle exportAll();

  /**
   * Parses and validates an uploaded import file against the current state of the realm,
   * without creating anything. Entries that already exist (matched on the same identifiers the
   * application already enforces uniqueness on) are reported as duplicates and excluded from the
   * batch that {@link #confirm} would act on; entries with invalid data or a reference to a
   * function/organization that resolves neither in Keycloak nor earlier in the same file are
   * reported as errors and likewise excluded.
   *
   * <p>The filtered batch is stored in {@code session} under a fresh, single-use id, superseding
   * any batch left over from an earlier dry-run in the same session.</p>
   *
   * @param fileContent the raw bytes of the uploaded JSON file
   * @param actorUserId the Keycloak subject of the superuser running the dry-run
   * @param session     the caller's HTTP session
   * @return the outcome of every entry in the file, plus the id of the resulting pending batch
   * @throws ImportValidationException if the file is not valid JSON, does not match the expected
   *                                    shape, carries an unsupported {@code schema_version}, or
   *                                    exceeds the size limits checked before per-entry validation
   */
  @NonNull ImportPreviewReport dryRun(
      byte @NonNull [] fileContent,
      @NonNull String actorUserId,
      @NonNull HttpSession session);

  /**
   * Creates every entry in the pending batch identified by {@code batchId}, in the order
   * functions, then organizations (with their function attachments), then users (with their
   * rights). Re-checks each entry against the current state of Keycloak immediately before
   * creating it — state may have changed since the dry-run — and re-reports it as a duplicate
   * rather than creating it again if so. A failure creating one entry is logged and reported as
   * an error; it does not stop the rest of the batch, since Keycloak offers no cross-entity
   * transaction to roll back.
   *
   * <p>The batch is removed from {@code session} once this method returns, whether or not every
   * entry succeeded — confirming is single-use.</p>
   *
   * @param batchId     the batch id returned by the {@link #dryRun} call this confirms
   * @param actorUserId the Keycloak subject of the confirming superuser; must match the one that
   *                    ran the dry-run
   * @param session     the caller's HTTP session
   * @return the outcome of every entry in the batch
   * @throws ImportBatchNotFoundException if no matching, unexpired batch is held in the session
   */
  @NonNull ImportReport confirm(
      @NonNull String batchId,
      @NonNull String actorUserId,
      @NonNull HttpSession session);

  /**
   * Discards the pending batch identified by {@code batchId} without creating anything.
   *
   * @param batchId the batch id to discard
   * @param session the caller's HTTP session
   * @return {@code true} if a matching batch was found and discarded, {@code false} otherwise
   */
  boolean cancel(@NonNull String batchId, @NonNull HttpSession session);

}
