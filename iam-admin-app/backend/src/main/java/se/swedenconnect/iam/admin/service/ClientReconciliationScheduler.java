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

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.service.model.ReconciliationReport;

/**
 * Runs {@link ClientReconciliationService} on a schedule to repair drift.
 *
 * <p>Reconciliation also runs whenever a client is created or updated, and whenever a function is
 * attached to or detached from an organization, so a realm in a consistent state does not depend
 * on this schedule. It exists for the cases those paths cannot cover: partial failures, a Keycloak
 * outage midway through an operation, and manual edits in the Keycloak admin console.</p>
 *
 * <p>Disabled unless {@code iam.admin.client-reconciliation.enabled} is set.</p>
 *
 * <p>No leader election is performed. Every reconciliation operation is idempotent and checks for
 * existence before creating, so concurrent runs across several application instances converge to
 * the same state rather than conflicting.</p>
 *
 * @author Felix Hellman
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "iam.admin.client-reconciliation", name = "enabled", havingValue = "true")
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class ClientReconciliationScheduler {

  private final ClientReconciliationService reconciliationService;
  private final IamAdminProperties properties;

  /**
   * Logs the active schedule at startup, so that an operator can see it in the configuration
   * summary rather than having to infer it from the absence of log output.
   */
  @PostConstruct
  public void logConfiguration() {
    final IamAdminProperties.ClientReconciliation config = this.properties.getClientReconciliation();
    log.info("Scheduled client reconciliation enabled, cron='{}'", config.getCron());
  }

  /**
   * Reconciles every managed client.
   *
   * <p>A Keycloak failure is logged and swallowed: the next run tries again, and a reconciliation
   * failure must not take down the scheduler.</p>
   */
  @Scheduled(cron = "${iam.admin.client-reconciliation.cron:0 */15 * * * *}")
  public void reconcile() {
    try {
      final ReconciliationReport report = this.reconciliationService.reconcileAll();
      if (!report.errors().isEmpty()) {
        log.warn("Scheduled client reconciliation completed with {} errors", report.errors().size());
      }
    }
    catch (final KeycloakAdminException e) {
      log.error("Scheduled client reconciliation failed: {}", e.getMessage(), e);
    }
  }
}
