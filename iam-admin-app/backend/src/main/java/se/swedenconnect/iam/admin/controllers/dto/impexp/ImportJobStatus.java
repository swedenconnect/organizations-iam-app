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

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The progress of an import that runs in the background, as returned when it is started by
 * {@code POST /api/import/{batchId}/confirm} and when it is polled through
 * {@code GET /api/import/jobs/{jobId}}.
 *
 * @param jobId identifies the import for polling
 * @param status {@link #RUNNING}, {@link #COMPLETED} or {@link #FAILED}
 * @param total the number of entries in the batch
 * @param processed the number of entries handled so far, whatever their outcome
 * @param startedAt when the import was started, ISO 8601
 * @param finishedAt when the import finished, ISO 8601, or {@code null} while it runs
 * @param error why the import stopped before handling every entry, set only when it failed
 * @param report the outcome of every entry handled, set once the import is no longer running
 * @author PF Plars
 */
public record ImportJobStatus(
    @JsonProperty("job_id") @NonNull String jobId,
    @JsonProperty("status") @NonNull String status,
    @JsonProperty("total") int total,
    @JsonProperty("processed") int processed,
    @JsonProperty("started_at") @NonNull String startedAt,
    @JsonProperty("finished_at") @Nullable String finishedAt,
    @JsonProperty("error") @Nullable String error,
    @JsonProperty("report") @Nullable ImportReport report) {

  /** The import is still creating entries. */
  public static final String RUNNING = "running";

  /** Every entry in the batch has been handled. */
  public static final String COMPLETED = "completed";

  /** The import stopped before every entry was handled. */
  public static final String FAILED = "failed";

}
