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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.autoconfigure.audit.AuditAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import se.swedenconnect.iam.admin.audit.AuditData;
import se.swedenconnect.iam.admin.audit.IamAuditEvent;
import se.swedenconnect.iam.admin.audit.StdoutAuditEventRepository;
import se.swedenconnect.spring.audit.autoconfigure.AuditRepositoryAutoConfiguration;
import se.swedenconnect.spring.audit.autoconfigure.AuditSupportAutoConfiguration;
import se.swedenconnect.spring.audit.repository.DelegatingAuditEventRepository;
import se.swedenconnect.spring.audit.tracing.CorrelationID;
import se.swedenconnect.spring.audit.tracing.CorrelationIDHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the repository that writes the audit events to standard output: that enabling it takes over from the
 * library's repository auto-configuration, that the line is one self-contained JSON object carrying the marker
 * fields, and that the {@code audit.repository.*} properties still apply.
 *
 * @author David Goldring
 */
class StdoutAuditRepositoryTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(AuditSupportAutoConfiguration.class,
          AuditRepositoryAutoConfiguration.class, AuditAutoConfiguration.class))
      .withUserConfiguration(AuditConfiguration.class, TestProperties.class)
      .withPropertyValues("spring.application.name=iam-admin", "audit.default-principal=system",
          "audit.log-lifecycle-events=false");

  private ListAppender<ILoggingEvent> appender;

  private ch.qos.logback.classic.Logger auditLogger;

  /**
   * Captures what the audit logger emits. The production appender's pattern is {@code %m%n}, so the logging event's
   * message is exactly the line that would reach standard output.
   */
  @BeforeEach
  void attachAppender() {
    this.auditLogger = ((LoggerContext) LoggerFactory.getILoggerFactory())
        .getLogger(StdoutAuditEventRepository.AUDIT_LOGGER_NAME);
    this.auditLogger.setLevel(Level.INFO);
    this.appender = new ListAppender<>();
    this.appender.start();
    this.auditLogger.addAppender(this.appender);
  }

  @AfterEach
  void detachAppender() {
    this.auditLogger.detachAppender(this.appender);
    this.appender.stop();
  }

  /** Enabled, the events arrive on the audit logger as one JSON object per line, with the marker fields added. */
  @Test
  void enabled_writesOneMarkedJsonObjectPerEvent() {
    this.runner.withPropertyValues("iam.admin.audit.stdout.enabled=true").run(context -> {
      CorrelationIDHolder.set(CorrelationID.of("flow-1"));
      try {
        context.publishEvent(IamAuditEvent.functionCreated("demo", null));
        context.publishEvent(IamAuditEvent.importCancelled("b1"));
      }
      finally {
        CorrelationIDHolder.clear();
      }
    });

    final List<ILoggingEvent> events = this.appender.list;
    assertThat(events).hasSize(2);

    final JsonMapper mapper = JsonMapper.builder().build();
    final JsonNode first = mapper.readTree(events.get(0).getFormattedMessage());

    // The marker fields the log infrastructure routes on.
    assertThat(first.get("log_type").asString()).isEqualTo("audit");
    assertThat(first.get("sensitive").asBoolean()).isTrue();
    assertThat(first.get("logger").asString()).isEqualTo(StdoutAuditEventRepository.AUDIT_LOGGER_NAME);

    // The event itself, unchanged from what the file and syslog backends write.
    assertThat(first.get("type").asString()).isEqualTo("function_created");
    assertThat(first.get("principal").asString()).isEqualTo("system");
    assertThat(first.get("correlation_id").asString()).isEqualTo("flow-1");
    assertThat(first.get("application").get("name").asString()).isEqualTo("iam-admin");
    assertThat(first.get("data").get("function_id").asString()).isEqualTo("demo");

    assertThat(mapper.readTree(events.get(1).getFormattedMessage()).get("type").asString())
        .isEqualTo("import_cancelled");
  }

  /** The events are also kept in memory, so the actuator's auditevents endpoint still has something to serve. */
  @Test
  void enabled_keepsEventsReadableThroughTheRepository() {
    this.runner.withPropertyValues("iam.admin.audit.stdout.enabled=true").run(context -> {
      context.publishEvent(IamAuditEvent.dataExported(new AuditData.Export(1, 2, 3)));

      final AuditEventRepository repository = context.getBean(AuditEventRepository.class);
      assertThat(repository).isInstanceOf(DelegatingAuditEventRepository.class);
      assertThat(repository.find(null, null, "data_exported")).hasSize(1);
    });
  }

  /** {@code audit.repository.exclude-events} still filters, even though the library's wiring has backed off. */
  @Test
  void enabled_stillHonoursTheExcludeEventsFilter() {
    this.runner
        .withPropertyValues("iam.admin.audit.stdout.enabled=true",
            "audit.repository.exclude-events=function_created")
        .run(context -> {
          context.publishEvent(IamAuditEvent.functionCreated("demo", null));
          context.publishEvent(IamAuditEvent.importCancelled("b1"));
        });

    assertThat(this.appender.list).hasSize(1);
    assertThat(JsonMapper.builder().build()
        .readTree(this.appender.list.get(0).getFormattedMessage()).get("type").asString())
        .isEqualTo("import_cancelled");
  }

  /** Left off, nothing is written to the audit logger and the library's own wiring is untouched. */
  @Test
  void disabled_leavesTheLibraryWiringAlone() {
    this.runner.run(context -> {
      context.publishEvent(IamAuditEvent.importCancelled("b1"));

      assertThat(context).doesNotHaveBean("stdoutAuditEventRepository");
      // The library still supplies its own in-memory repository, so the event is readable.
      assertThat(context.getBean(AuditEventRepository.class).find(null, null, "import_cancelled")).hasSize(1);
    });

    assertThat(this.appender.list).isEmpty();
  }

  /** The application's own properties are not on the classpath of this slice, so they are enabled explicitly. */
  @EnableConfigurationProperties(IamAdminProperties.class)
  static class TestProperties {
  }

}
