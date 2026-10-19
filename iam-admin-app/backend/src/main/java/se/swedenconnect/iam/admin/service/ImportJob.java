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
import org.jspecify.annotations.Nullable;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportItemOutcome;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportJobStatus;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportReport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An import running in the background. It is written by the import thread and read by the
 * requests polling it, so every field that changes is safe to read from another thread.
 *
 * @author PF Plars
 */
final class ImportJob {

  /** How long a finished import can still be polled for its report. */
  static final Duration RETENTION = Duration.ofHours(1);

  private final String jobId;
  private final String ownerUserId;
  private final Instant startedAt;
  private final int total;

  private final AtomicInteger processed = new AtomicInteger();
  private final List<ImportItemOutcome> functions = Collections.synchronizedList(new ArrayList<>());
  private final List<ImportItemOutcome> organizations = Collections.synchronizedList(new ArrayList<>());
  private final List<ImportItemOutcome> users = Collections.synchronizedList(new ArrayList<>());

  private volatile String status = ImportJobStatus.RUNNING;
  private volatile @Nullable Instant finishedAt;
  private volatile @Nullable String error;

  ImportJob(final @NonNull String jobId, final @NonNull String ownerUserId, final int total) {
    this.jobId = jobId;
    this.ownerUserId = ownerUserId;
    this.startedAt = Instant.now();
    this.total = total;
  }

  @NonNull String jobId() {
    return this.jobId;
  }

  @NonNull String ownerUserId() {
    return this.ownerUserId;
  }

  boolean isRunning() {
    return ImportJobStatus.RUNNING.equals(this.status);
  }

  /** Whether the job finished long enough ago that it no longer has to be kept. */
  boolean isExpired() {
    final Instant finished = this.finishedAt;
    return finished != null && Instant.now().isAfter(finished.plus(RETENTION));
  }

  void addFunction(final @NonNull ImportItemOutcome outcome) {
    this.functions.add(outcome);
    this.processed.incrementAndGet();
  }

  void addOrganization(final @NonNull ImportItemOutcome outcome) {
    this.organizations.add(outcome);
    this.processed.incrementAndGet();
  }

  void addUser(final @NonNull ImportItemOutcome outcome) {
    this.users.add(outcome);
    this.processed.incrementAndGet();
  }

  @NonNull List<ImportItemOutcome> functions() {
    return this.functions;
  }

  @NonNull List<ImportItemOutcome> organizations() {
    return this.organizations;
  }

  @NonNull List<ImportItemOutcome> users() {
    return this.users;
  }

  void complete() {
    this.finishedAt = Instant.now();
    this.status = ImportJobStatus.COMPLETED;
  }

  void fail(final @NonNull String error) {
    this.error = error;
    this.finishedAt = Instant.now();
    this.status = ImportJobStatus.FAILED;
  }

  /**
   * Returns the current progress. The report is included once the job is no longer running, and
   * then also for a failed job, since it tells what was done before the failure.
   */
  @NonNull ImportJobStatus snapshot() {
    final String currentStatus = this.status;
    final Instant finished = this.finishedAt;
    final ImportReport report = ImportJobStatus.RUNNING.equals(currentStatus)
        ? null
        : new ImportReport(copyOf(this.functions), copyOf(this.organizations), copyOf(this.users));
    return new ImportJobStatus(
        this.jobId,
        currentStatus,
        this.total,
        this.processed.get(),
        this.startedAt.toString(),
        finished != null ? finished.toString() : null,
        this.error,
        report);
  }

  private static @NonNull List<ImportItemOutcome> copyOf(final @NonNull List<ImportItemOutcome> outcomes) {
    synchronized (outcomes) {
      return List.copyOf(outcomes);
    }
  }

}
