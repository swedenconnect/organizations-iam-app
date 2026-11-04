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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import se.swedenconnect.iam.admin.audit.RecordingAuditPublisher;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportItemOutcome;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportJobStatus;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportPreviewReport;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.FunctionInfo;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.spring.audit.AuditEvent;
import se.swedenconnect.spring.audit.repository.AuditEventWriteException;
import se.swedenconnect.spring.audit.tracing.CorrelationID;
import se.swedenconnect.spring.audit.tracing.CorrelationIDHolder;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static se.swedenconnect.iam.admin.audit.RecordingAuditPublisher.object;

/**
 * Tests that an import produces the same audit events as the manual operations, carrying the
 * confirming user, the session's correlation ID and the batch and job, and that an audit write
 * failure stops the import.
 *
 * @author Martin Lindström
 */
class ImportAuditTest {

  private static final String ORG = "2021006883";
  private static final String NEW_ORG = "5560000001";
  private static final String ACTOR = "actor";

  private final RecordingAuditPublisher publisher = new RecordingAuditPublisher();
  private final KeycloakAdminClient keycloakAdminClient = mock(KeycloakAdminClient.class);
  private final OrganizationService organizationService = mock(OrganizationService.class);
  private final IamAdminProperties properties = new IamAdminProperties();
  private final ExecutorService importThread = Executors.newSingleThreadExecutor();

  private MockHttpSession session;

  @BeforeEach
  void setUp() {
    this.session = new MockHttpSession();
    this.properties.setAllowOrgRights(true);
    final LocalizedString name = new LocalizedString();
    name.add("sv", "Wallet");
    name.add("en", "Wallet");
    when(this.keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(new FunctionInfo("walletreg", name, null)));
    when(this.keycloakAdminClient.fetchAllOrganizationGroups()).thenReturn(List.of(
        new OrganizationInfo(ORG, "Digg", null, "group-id", List.of("walletreg"), null, null)));
    when(this.keycloakAdminClient.findUserIdByPersonalIdentityNumber(anyString())).thenReturn(Optional.empty());
    when(this.keycloakAdminClient.createUser(any(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn("created-1", "created-2");
    when(this.organizationService.create(anyString(), anyString(), any(), any(), any(), any()))
        .thenAnswer(inv -> new OrganizationInfo(inv.getArgument(0), inv.getArgument(1), null,
            inv.getArgument(0) + "-group", List.of(), null, null));

    // The request thread: the confirming user and the session's correlation ID
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(ACTOR, null, List.of()));
    CorrelationIDHolder.set(CorrelationID.of("session-flow"));
  }

  @AfterEach
  void cleanUp() {
    SecurityContextHolder.clearContext();
    CorrelationIDHolder.clear();
    this.importThread.shutdownNow();
  }

  /** Every object the import creates produces its normal event, on the import's own thread. */
  @Test
  void import_producesTheEventsOfTheManualOperations() throws Exception {
    final ImportExportServiceImpl service = this.service(this.onImportThread());
    final ImportPreviewReport report = this.dryRun(service, FULL_FILE);
    // The setting changes between the dry-run and the import, so the organization-wide right is refused
    this.properties.setAllowOrgRights(false);

    final ImportJobStatus started = service.startImport(report.batchId(), ACTOR, this.session);

    assertThat(this.publisher.types()).containsExactly(
        "import_started",
        "function_created", "organization_created", "function_attached", "function_attached",
        "user_created", "user_right_assigned", "operation_rejected",
        "import_completed");

    final AuditEvent importStarted = this.publisher.single("import_started");
    assertThat(object(importStarted, "import")).containsExactly(
        Map.entry("batch_id", report.batchId()), Map.entry("job_id", started.jobId()),
        Map.entry("function_count", 1), Map.entry("organization_count", 1), Map.entry("user_count", 1));

    for (final AuditEvent event : this.publisher.events()) {
      assertThat(event.getPrincipal()).as(event.getType()).isEqualTo(ACTOR);
      assertThat(event.getCorrelationId()).as(event.getType()).isEqualTo(CorrelationID.of("session-flow"));
      assertThat(object(event, "import")).as(event.getType())
          .containsEntry("batch_id", report.batchId())
          .containsEntry("job_id", started.jobId());
    }

    assertThat(object(this.publisher.single("user_created"), "user"))
        .containsEntry("user_id", "created-1")
        .containsEntry("display_name", "Anna Andersson")
        .containsEntry("personal_identity_number", "197001011234")
        .containsEntry("temporary_password_set", false);
    assertThat(this.publisher.single("user_right_assigned").getData())
        .containsEntry("organization_identifier", NEW_ORG)
        .containsEntry("function_id", "demo");
    assertThat(object(this.publisher.single("operation_rejected"), "operation"))
        .containsExactly(Map.entry("name", "assign_user_right"), Map.entry("reason", "org_rights_disabled"));
    assertThat(object(this.publisher.single("import_completed"), "import"))
        .containsEntry("status", "completed")
        .containsEntry("created_count", 3)
        .containsEntry("error_count", 0)
        .doesNotContainKey("message");

    // The import thread is left without a correlation ID
    final Future<CorrelationID> left = this.importThread.submit(CorrelationIDHolder::get);
    assertThat(left.get(5, TimeUnit.SECONDS)).isNull();
  }

  /** Skipped duplicates produce no event. */
  @Test
  void import_skippedDuplicates_noEvents() {
    final ImportExportServiceImpl service = this.service(Runnable::run);
    final ImportPreviewReport report = this.dryRun(service, USER_FILE);
    // Becomes a duplicate between the dry-run and the import
    when(this.keycloakAdminClient.findUserIdByPersonalIdentityNumber("197001011234")).thenReturn(Optional.of("x"));

    service.startImport(report.batchId(), ACTOR, this.session);

    assertThat(this.publisher.types()).containsExactly("import_started", "import_completed");
  }

  /** A failed entry produces {@code operation_failed}, and the import goes on. */
  @Test
  void import_failedEntry_operationFailed() {
    when(this.organizationService.create(eq(NEW_ORG), anyString(), any(), any(), any(), any()))
        .thenThrow(new KeycloakAdminException("boom"));
    final ImportExportServiceImpl service = this.service(Runnable::run);

    service.startImport(this.dryRun(service, ORG_FILE).batchId(), ACTOR, this.session);

    final AuditEvent failed = this.publisher.single("operation_failed");
    assertThat(object(failed, "operation")).containsExactly(Map.entry("name", "create_organization"));
    assertThat(failed.getData()).containsEntry("organization_identifier", NEW_ORG);
    assertThat(object(this.publisher.single("import_completed"), "import"))
        .containsEntry("status", "completed")
        .containsEntry("error_count", 1);
  }

  /** An import that stops part-way still produces {@code import_completed}, with status failed. */
  @Test
  void import_stoppedPartWay_completedWithStatusFailed() {
    final ImportExportServiceImpl service = this.service(Runnable::run);
    final ImportPreviewReport report = this.dryRun(service, ORG_FILE);
    when(this.keycloakAdminClient.fetchAllOrganizationGroups()).thenThrow(new KeycloakAdminException("down"));

    service.startImport(report.batchId(), ACTOR, this.session);

    assertThat(object(this.publisher.single("import_completed"), "import"))
        .containsEntry("status", "failed")
        .containsEntry("message", "The import stopped before every entry was handled: down");
  }

  /** An import that could not be started still produces {@code import_completed}, with status failed. */
  @Test
  void import_couldNotBeStarted_completedWithStatusFailed() {
    final ImportExportServiceImpl service = this.service(r -> {
      throw new RejectedExecutionException("full");
    });

    service.startImport(this.dryRun(service, ORG_FILE).batchId(), ACTOR, this.session);

    assertThat(this.publisher.types()).containsExactly("import_started", "import_completed");
    assertThat(object(this.publisher.single("import_completed"), "import")).containsEntry("status", "failed");
  }

  /**
   * An audit write failure stops the import with status failed. The entry whose event could not be
   * written is reported as created, since it was, and nothing more is created.
   */
  @Test
  void import_auditWriteFailure_stopsTheImport() {
    this.publisher.failWritesOf("user_created");
    final ImportExportServiceImpl service = this.service(Runnable::run);

    final ImportJobStatus job = service.startImport(this.dryRun(service, TWO_USER_FILE).batchId(), ACTOR, this.session);

    assertThat(job.status()).isEqualTo(ImportJobStatus.FAILED);
    assertThat(job.error()).contains("audit entry could not be written");
    assertThat(job.report().users()).extracting(ImportItemOutcome::status).containsExactly("created");
    verify(this.keycloakAdminClient, times(1)).createUser(any(), anyString(), any(), any(), any(), any(), any());
    assertThat(object(this.publisher.single("import_completed"), "import")).containsEntry("status", "failed");
  }

  /** If {@code import_completed} cannot be written either, the job's report still shows the failure. */
  @Test
  void import_completedWriteFailure_shownOnTheJob() {
    this.publisher.failWritesOf("import_completed");
    final ImportExportServiceImpl service = this.service(Runnable::run);

    final ImportJobStatus job = service.startImport(this.dryRun(service, ORG_FILE).batchId(), ACTOR, this.session);

    final ImportJobStatus polled = service.getImportJob(job.jobId(), ACTOR).orElseThrow();
    assertThat(polled.status()).isEqualTo(ImportJobStatus.FAILED);
    assertThat(polled.error()).contains("import_completed audit entry could not be written");
  }

  /** A failure to write {@code import_started} fails the request and imports nothing. */
  @Test
  void import_startedWriteFailure_nothingImported() {
    this.publisher.failWritesOf("import_started");
    final ImportExportServiceImpl service = this.service(Runnable::run);
    final ImportPreviewReport report = this.dryRun(service, ORG_FILE);

    assertThatThrownBy(() -> service.startImport(report.batchId(), ACTOR, this.session))
        .isInstanceOf(AuditEventWriteException.class);
    verify(this.organizationService, times(0)).create(anyString(), anyString(), any(), any(), any(), any());
  }

  /** The dry-run is not audited. */
  @Test
  void dryRun_notAudited() {
    this.dryRun(this.service(Runnable::run), FULL_FILE);

    assertThat(this.publisher.events()).isEmpty();
  }

  private ImportExportServiceImpl service(final Executor executor) {
    return new ImportExportServiceImpl(this.keycloakAdminClient, this.organizationService, new ObjectMapper(),
        this.properties, this.publisher, executor);
  }

  /** Runs the import on a thread of its own, waiting for it to finish. */
  private Executor onImportThread() {
    return task -> {
      try {
        this.importThread.submit(task).get(10, TimeUnit.SECONDS);
      }
      catch (final Exception e) {
        throw new IllegalStateException(e);
      }
    };
  }

  private ImportPreviewReport dryRun(final ImportExportServiceImpl service, final String file) {
    return service.dryRun(file.getBytes(StandardCharsets.UTF_8), ACTOR, this.session);
  }

  private static final String FULL_FILE = """
      {
        "schema_version": "1.0",
        "functions": [ { "id": "demo", "name#sv": "Demo", "name#en": "Demo" } ],
        "organizations": [
          { "org_identifier": "5560000001", "legal_name": "Ett AB", "attached_functions": ["walletreg", "demo"] }
        ],
        "users": [
          {
            "name": "Anna Andersson",
            "personal_identity_number": "197001011234",
            "rights": [
              { "org_identifier": "5560000001", "function_id": "demo", "right": "read" },
              { "org_identifier": "5560000001", "right": "write" }
            ]
          }
        ]
      }
      """;

  private static final String ORG_FILE = """
      {
        "schema_version": "1.0",
        "functions": [],
        "organizations": [ { "org_identifier": "5560000001", "legal_name": "Ett AB", "attached_functions": [] } ],
        "users": []
      }
      """;

  private static final String USER_FILE = """
      {
        "schema_version": "1.0",
        "functions": [],
        "organizations": [],
        "users": [ { "name": "Anna Andersson", "personal_identity_number": "197001011234", "rights": [] } ]
      }
      """;

  private static final String TWO_USER_FILE = """
      {
        "schema_version": "1.0",
        "functions": [],
        "organizations": [],
        "users": [
          { "name": "Anna Andersson", "personal_identity_number": "197001011234", "rights": [] },
          { "name": "Bo Berg", "personal_identity_number": "197002021234", "rights": [] }
        ]
      }
      """;

}
