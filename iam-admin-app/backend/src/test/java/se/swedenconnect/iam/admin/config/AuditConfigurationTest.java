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
package se.swedenconnect.iam.admin.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.autoconfigure.audit.AuditAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import se.swedenconnect.iam.admin.audit.AuditData;
import se.swedenconnect.iam.admin.audit.IamAuditEvent;
import se.swedenconnect.spring.audit.autoconfigure.AuditRepositoryAutoConfiguration;
import se.swedenconnect.spring.audit.autoconfigure.AuditSupportAutoConfiguration;
import se.swedenconnect.spring.audit.tracing.CorrelationID;
import se.swedenconnect.spring.audit.tracing.CorrelationIDHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the audit chain as the application configures it: events published in the application
 * context are written to the configured repository by the actuator's audit listener.
 *
 * @author Martin Lindström
 */
class AuditConfigurationTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(AuditSupportAutoConfiguration.class,
          AuditRepositoryAutoConfiguration.class, AuditAutoConfiguration.class))
      .withUserConfiguration(AuditConfiguration.class)
      .withPropertyValues("spring.application.name=iam-admin", "audit.default-principal=system");

  /** With a file repository, as in the local and compose environments, each event is one line of JSON. */
  @Test
  void fileRepository_oneJsonEventPerLine(@TempDir final Path dir) throws Exception {
    final Path file = dir.resolve("audit.log");
    this.runner
        .withPropertyValues("audit.repository.file.log-file=" + file, "audit.log-lifecycle-events=false")
        .run(context -> {
          CorrelationIDHolder.set(CorrelationID.of("flow-1"));
          try {
            context.publishEvent(IamAuditEvent.functionCreated("demo", null));
            context.publishEvent(IamAuditEvent.importCancelled("b1"));
          }
          finally {
            CorrelationIDHolder.clear();
          }
        });

    final List<String> lines = Files.readAllLines(file).stream().filter(l -> !l.isBlank()).toList();
    assertThat(lines).hasSize(2);
    final JsonMapper mapper = JsonMapper.builder().build();
    final JsonNode first = mapper.readTree(lines.get(0));
    assertThat(first.get("type").asString()).isEqualTo("function_created");
    assertThat(first.get("principal").asString()).isEqualTo("system");
    assertThat(first.get("correlation_id").asString()).isEqualTo("flow-1");
    assertThat(first.get("application").get("name").asString()).isEqualTo("iam-admin");
    assertThat(first.get("data").get("function_id").asString()).isEqualTo("demo");
    assertThat(mapper.readTree(lines.get(1)).get("type").asString()).isEqualTo("import_cancelled");
  }

  /** Without a repository the events are kept in memory, and the lifecycle events are on. */
  @Test
  void defaultRepository_inMemoryWithLifecycleEvents() {
    this.runner.run(context -> {
      context.publishEvent(IamAuditEvent.dataExported(new AuditData.Export(1, 2, 3)));

      final AuditEventRepository repository = context.getBean(AuditEventRepository.class);
      assertThat(repository.find(null, null, "data_exported")).hasSize(1);
      assertThat(context).hasBean("applicationReadyEventTransformer");
    });
  }

}
