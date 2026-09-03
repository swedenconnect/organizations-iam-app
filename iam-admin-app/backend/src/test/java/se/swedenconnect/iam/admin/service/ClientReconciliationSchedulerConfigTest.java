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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import se.swedenconnect.iam.admin.config.IamAdminProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies how {@link ClientReconciliationScheduler} is wired: it must stay out of the context
 * unless scheduling is switched on, and it must start with no cron expression configured.
 *
 * @author Felix Hellman
 */
class ClientReconciliationSchedulerConfigTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          ConfigurationPropertiesAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
      .withUserConfiguration(TestConfiguration.class)
      .withPropertyValues("iam.admin.admin-api-base=https://keycloak.example.se/admin/realms/orgiam");

  @Test
  @DisplayName("The scheduler is absent unless reconciliation is enabled")
  void absentByDefault() {
    this.runner.run(context ->
        assertThat(context).doesNotHaveBean(ClientReconciliationScheduler.class));
  }

  @Test
  @DisplayName("Enabling reconciliation without a cron expression still starts")
  void startsWithoutExplicitCron() {
    this.runner
        .withPropertyValues("iam.admin.client-reconciliation.enabled=true")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(ClientReconciliationScheduler.class);
        });
  }

  @Test
  @DisplayName("An explicit cron expression is accepted")
  void startsWithExplicitCron() {
    this.runner
        .withPropertyValues(
            "iam.admin.client-reconciliation.enabled=true",
            "iam.admin.client-reconciliation.cron=0 0 * * * *")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(IamAdminProperties.class).getClientReconciliation().getCron())
              .isEqualTo("0 0 * * * *");
        });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(IamAdminProperties.class)
  @Import(ClientReconciliationScheduler.class)
  static class TestConfiguration {

    @Bean
    ClientReconciliationService clientReconciliationService() {
      return mock(ClientReconciliationService.class);
    }
  }
}
