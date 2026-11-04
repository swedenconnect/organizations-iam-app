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
import org.junit.jupiter.api.Test;
import se.swedenconnect.iam.admin.audit.RecordingAuditPublisher;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.service.model.ReconciliationReport;
import se.swedenconnect.spring.audit.AuditEvent;
import se.swedenconnect.spring.audit.tracing.CorrelationID;
import se.swedenconnect.spring.audit.tracing.CorrelationIDHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static se.swedenconnect.iam.admin.audit.RecordingAuditPublisher.object;

/**
 * Tests the audit events of the scheduled client reconciliation.
 *
 * @author Martin Lindström
 */
class ClientReconciliationSchedulerAuditTest {

  private final RecordingAuditPublisher publisher = new RecordingAuditPublisher();
  private final ClientReconciliationService reconciliationService = mock(ClientReconciliationService.class);
  private final ClientReconciliationScheduler scheduler =
      new ClientReconciliationScheduler(this.reconciliationService, new IamAdminProperties(), this.publisher);

  @AfterEach
  void cleanUp() {
    CorrelationIDHolder.clear();
  }

  /** Each run has a correlation ID of its own, which is gone once the run is over. */
  @Test
  void runsHaveDifferentCorrelationIds() {
    when(this.reconciliationService.reconcileAll()).thenReturn(new ReconciliationReport(2, 3, 1, List.of()));
    CorrelationIDHolder.set(CorrelationID.of("left-over"));

    this.scheduler.reconcile();
    this.scheduler.reconcile();

    final List<AuditEvent> events = this.publisher.all("clients_reconciled");
    assertThat(events).hasSize(2);
    assertThat(events).extracting(AuditEvent::getCorrelationId)
        .doesNotContainNull()
        .doesNotContain(CorrelationID.of("left-over"))
        .doesNotHaveDuplicates();
    assertThat(events).extracting(AuditEvent::getPrincipal).containsOnly("system");
    assertThat(object(events.getFirst(), "reconciliation")).containsExactly(
        Map.entry("trigger", "scheduled"), Map.entry("client_count", 2),
        Map.entry("created", 3), Map.entry("removed", 1));
    assertThat(CorrelationIDHolder.get()).isNull();
  }

  /** A run with nothing to do produces nothing. */
  @Test
  void nothingToDo_noEvent() {
    when(this.reconciliationService.reconcileAll()).thenReturn(new ReconciliationReport(2, 0, 0, List.of()));

    this.scheduler.reconcile();

    assertThat(this.publisher.events()).isEmpty();
  }

  /** A run with errors only is recorded. */
  @Test
  void errors_recorded() {
    when(this.reconciliationService.reconcileAll()).thenReturn(new ReconciliationReport(2, 0, 0, List.of("oops")));

    this.scheduler.reconcile();

    assertThat(object(this.publisher.single("clients_reconciled"), "reconciliation"))
        .containsEntry("errors", List.of("oops"));
  }

  /** A failed run raises a system alert. */
  @Test
  void failedRun_systemAlert() {
    when(this.reconciliationService.reconcileAll()).thenThrow(new KeycloakAdminException("down"));

    this.scheduler.reconcile();

    final AuditEvent event = this.publisher.single("system_alert");
    assertThat(event.getCorrelationId()).isNotNull();
    assertThat(CorrelationIDHolder.get()).isNull();
  }

}
