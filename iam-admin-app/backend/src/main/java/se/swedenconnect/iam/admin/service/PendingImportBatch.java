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

import org.jspecify.annotations.NonNull;
import se.swedenconnect.iam.admin.controllers.dto.BundleFunctionEntry;
import se.swedenconnect.iam.admin.controllers.dto.BundleOrganizationEntry;
import se.swedenconnect.iam.admin.controllers.dto.BundleUserEntry;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * The already-validated, duplicate-filtered contents of one dry-run, held server-side (in the
 * caller's HTTP session) between {@code POST /api/import/dry-run} and
 * {@code POST /api/import/{batchId}/confirm} so the uploaded file need not be sent twice.
 *
 * <p>Bound to the superuser who ran the dry-run and single-use: {@link ImportExportServiceImpl}
 * removes it from the session as soon as it is confirmed or cancelled, and refuses to act on it
 * past {@link #TTL}.</p>
 *
 * @author PF Plars
 */
record PendingImportBatch(
    @NonNull String batchId,
    @NonNull String ownerUserId,
    @NonNull Instant createdAt,
    @NonNull List<BundleFunctionEntry> functions,
    @NonNull List<BundleOrganizationEntry> organizations,
    @NonNull List<BundleUserEntry> users) implements Serializable {

  /** How long a dry-run result remains confirmable before it must be re-run. */
  static final java.time.Duration TTL = java.time.Duration.ofMinutes(15);

  boolean isExpired() {
    return Instant.now().isAfter(this.createdAt.plus(TTL));
  }
}
