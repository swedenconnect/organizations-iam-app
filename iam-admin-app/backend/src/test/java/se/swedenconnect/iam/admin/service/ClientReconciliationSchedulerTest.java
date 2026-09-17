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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.service.model.ReconciliationReport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test cases for {@link ClientReconciliationScheduler}.
 *
 * @author Felix Hellman
 */
@ExtendWith(MockitoExtension.class)
class ClientReconciliationSchedulerTest {

  @Mock
  private ClientReconciliationService reconciliationService;

  private IamAdminProperties properties;

  private ClientReconciliationScheduler scheduler;

  @BeforeEach
  void setUp() {
    this.properties = new IamAdminProperties();
    this.scheduler = new ClientReconciliationScheduler(this.reconciliationService, this.properties);
  }

  @Test
  @DisplayName("Reconciliation defaults to disabled, every 15 minutes")
  void defaults() {
    final IamAdminProperties.ClientReconciliation config = this.properties.getClientReconciliation();

    assertThat(config.isEnabled()).isFalse();
    assertThat(config.getCron()).isEqualTo("0 */15 * * * *");
  }

  @Test
  @DisplayName("A scheduled run reconciles every client")
  void runsAcrossEveryClient() {
    when(this.reconciliationService.reconcileAll())
        .thenReturn(new ReconciliationReport(2, 6, 3, List.of()));

    this.scheduler.reconcile();

    verify(this.reconciliationService).reconcileAll();
  }

  @Test
  @DisplayName("A Keycloak failure does not propagate out of the scheduled run")
  void keycloakFailureIsSwallowed() {
    when(this.reconciliationService.reconcileAll())
        .thenThrow(new KeycloakAdminException("Connection refused"));

    assertThatCode(() -> this.scheduler.reconcile()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("A run that reports per-client errors still completes")
  void perClientErrorsDoNotFailTheRun() {
    when(this.reconciliationService.reconcileAll())
        .thenReturn(new ReconciliationReport(2, 3, 0, List.of("failed for 'org:demo' on client 'x'")));

    assertThatCode(() -> this.scheduler.reconcile()).doesNotThrowAnyException();
  }
}
