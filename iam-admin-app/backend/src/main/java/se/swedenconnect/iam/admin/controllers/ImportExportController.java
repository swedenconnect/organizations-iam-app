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
package se.swedenconnect.iam.admin.controllers;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportExportBundle;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportJobStatus;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportPreviewReport;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.service.ImportAlreadyRunningException;
import se.swedenconnect.iam.admin.service.ImportBatchNotFoundException;
import se.swedenconnect.iam.admin.service.ImportExportService;
import se.swedenconnect.iam.admin.service.ImportValidationException;

import java.io.IOException;

/**
 * REST controller for the superuser-only export/import feature.
 *
 * <p>Import is a two-step flow. {@code POST /import/dry-run} validates an uploaded file against
 * the current state of the realm without creating anything, and stores the resulting
 * duplicate-filtered batch in the caller's session under the returned {@code batch_id}.
 * {@code POST /import/{batchId}/confirm} creates that batch's contents. The file is uploaded
 * only once.</p>
 *
 * @author PF Plars
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ImportExportController {

  private final ImportExportService importExportService;

  /**
   * Exports every function, organization and non-superuser user (with rights) in the realm as a
   * single downloadable JSON file.
   *
   * @param request the HTTP servlet request
   * @return 200 with the export bundle, 403 if not superuser
   */
  @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> exportAll(final HttpServletRequest request) {
    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null || !data.currentUserIsSuperuser()) {
      log.info("GET /api/export — rejected: caller is not a superuser");
      return ResponseEntity.status(403).build();
    }

    final ImportExportBundle bundle = this.importExportService.exportAll();
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"iam-export.json\"")
        .body(bundle);
  }

  /**
   * Validates an uploaded import file without creating anything, and stores the resulting batch
   * in the session for a subsequent {@link #confirm}.
   *
   * @param file    the uploaded JSON file
   * @param request the HTTP servlet request
   * @return 200 with the preview report, 400 on a malformed file, 403 if not superuser
   */
  @PostMapping(value = "/import/dry-run", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> dryRun(
      @RequestParam("file") final MultipartFile file,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null || !data.currentUserIsSuperuser()) {
      log.info("POST /api/import/dry-run — rejected: caller is not a superuser");
      return ResponseEntity.status(403).build();
    }

    final String actorUserId = getCurrentUserId();
    if (actorUserId == null) {
      return ResponseEntity.status(403).build();
    }

    if (file.isEmpty()) {
      return ResponseEntity.badRequest().body("file must not be empty");
    }

    try {
      final ImportPreviewReport report =
          this.importExportService.dryRun(file.getBytes(), actorUserId, request.getSession(true));
      log.info("POST /api/import/dry-run — superuser '{}' ran dry-run, batchId={}", actorUserId, report.batchId());
      return ResponseEntity.ok(report);
    }
    catch (final IOException e) {
      log.warn("POST /api/import/dry-run — could not read uploaded file: {}", e.getMessage());
      return ResponseEntity.badRequest().body("could not read uploaded file");
    }
    catch (final ImportValidationException e) {
      log.info("POST /api/import/dry-run — rejected: {}", e.getMessage());
      return ResponseEntity.badRequest().body(e.getMessage());
    }
  }

  /**
   * Starts creating every entry in the pending batch identified by {@code batchId}. The import
   * runs in the background; its progress and final report are read with {@link #getImportJob}.
   *
   * @param batchId the batch id returned by the preceding {@link #dryRun} call
   * @param request the HTTP servlet request
   * @return 202 with the progress of the started import, 403 if not superuser,
   *         409 if the batch is unknown, expired, or belongs to another session, or if another
   *         import is still running
   */
  @PostMapping(value = "/import/{batchId}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> confirm(
      @PathVariable final String batchId,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null || !data.currentUserIsSuperuser()) {
      log.info("POST /api/import/{}/confirm — rejected: caller is not a superuser", batchId);
      return ResponseEntity.status(403).build();
    }

    final String actorUserId = getCurrentUserId();
    if (actorUserId == null) {
      return ResponseEntity.status(403).build();
    }

    try {
      final ImportJobStatus job =
          this.importExportService.startImport(batchId, actorUserId, request.getSession(true));
      log.info("POST /api/import/{}/confirm — superuser '{}' started import, jobId={}",
          batchId, actorUserId, job.jobId());
      return ResponseEntity.accepted().body(job);
    }
    catch (final ImportBatchNotFoundException | ImportAlreadyRunningException e) {
      log.info("POST /api/import/{}/confirm — rejected: {}", batchId, e.getMessage());
      return ResponseEntity.status(409).body(e.getMessage());
    }
  }

  /**
   * Returns the progress of an import started by {@link #confirm}, and its report once it has
   * finished.
   *
   * @param jobId the job id returned by {@link #confirm}
   * @param request the HTTP servlet request
   * @return 200 with the progress, 403 if not superuser, 404 if no such import was started by the
   *         caller or it finished too long ago
   */
  @GetMapping(value = "/import/jobs/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> getImportJob(
      @PathVariable final String jobId,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null || !data.currentUserIsSuperuser()) {
      log.info("GET /api/import/jobs/{} — rejected: caller is not a superuser", jobId);
      return ResponseEntity.status(403).build();
    }

    final String actorUserId = getCurrentUserId();
    if (actorUserId == null) {
      return ResponseEntity.status(403).build();
    }

    return this.importExportService.getImportJob(jobId, actorUserId)
        .<ResponseEntity<?>>map(job -> ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(job))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Discards the pending batch identified by {@code batchId} without creating anything.
   *
   * @param batchId the batch id to discard
   * @param request the HTTP servlet request
   * @return 204 on success, 403 if not superuser, 404 if no matching batch is pending
   */
  @DeleteMapping(value = "/import/{batchId}")
  public ResponseEntity<?> cancel(
      @PathVariable final String batchId,
      final HttpServletRequest request) {

    final AdminSessionData data = AdminSessionBootstrapHandler.resolveSession(request).orElse(null);
    if (data == null || !data.currentUserIsSuperuser()) {
      log.info("DELETE /api/import/{} — rejected: caller is not a superuser", batchId);
      return ResponseEntity.status(403).build();
    }

    final boolean removed = this.importExportService.cancel(batchId, request.getSession(true));
    if (!removed) {
      log.info("DELETE /api/import/{} — no matching pending batch", batchId);
      return ResponseEntity.notFound().build();
    }
    log.info("DELETE /api/import/{} — batch discarded", batchId);
    return ResponseEntity.noContent().build();
  }

  private static @Nullable String getCurrentUserId() {
    final var auth = SecurityContextHolder.getContext().getAuthentication();
    return (auth != null && auth.getPrincipal() instanceof final OidcUser oidc)
        ? oidc.getSubject()
        : null;
  }

}
