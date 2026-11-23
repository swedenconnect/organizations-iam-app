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
package se.swedenconnect.iam.admin.audit;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.audit.AuditEvent;
import se.swedenconnect.spring.audit.repository.AbstractAuditEventRepository;
import se.swedenconnect.spring.audit.repository.AuditEventMapper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * An audit event repository that writes each event to standard output as a single line of JSON, for a container log
 * collector to pick up and route.
 *
 * <p>The event itself is serialized by the library's {@link AuditEventMapper}, so the JSON is the same as the file and
 * syslog backends produce. Three fields are added to the object to let the log infrastructure tell an audit event apart
 * from an application log record, and to let it route the events that carry personal data:</p>
 *
 * <pre>
 * {"log_type":"audit","sensitive":true,"logger":"se.swedenconnect.iam.admin.AUDIT","type":"login_succeeded",...}
 * </pre>
 *
 * <p>The line is written through the logger named {@value #AUDIT_LOGGER_NAME}, which {@code logback-spring.xml}
 * attaches to an appender whose pattern is the message and nothing else, so that the line is valid JSON on its own.
 * The logger name is repeated inside the object because the pattern does not emit it.</p>
 *
 * <p><b>A write to standard output cannot be detected as failed.</b> The write succeeds as soon as the bytes are
 * accepted, so if the collector is down, or lines are dropped under back pressure, this repository cannot tell. The
 * {@code audit.repository.throw-on-write-fail} policy therefore cannot uphold its guarantee here, and a durable
 * repository is needed for that.</p>
 *
 * @author David Goldring
 */
public class StdoutAuditEventRepository extends AbstractAuditEventRepository {

  /** The name of the logger that carries the audit events, and nothing else. */
  public static final String AUDIT_LOGGER_NAME = "se.swedenconnect.iam.admin.AUDIT";

  /** The field marking the line as an audit event rather than an application log record. */
  public static final String LOG_TYPE_FIELD = "log_type";

  /** The value of {@link #LOG_TYPE_FIELD}. */
  public static final String LOG_TYPE_VALUE = "audit";

  /** The field marking the line as carrying personal data, so that it can be routed and access controlled. */
  public static final String SENSITIVE_FIELD = "sensitive";

  /** The field repeating the logger name, which the appender's pattern does not emit. */
  public static final String LOGGER_FIELD = "logger";

  /** The logger the events are written to. */
  private static final Logger auditLogger = LoggerFactory.getLogger(AUDIT_LOGGER_NAME);

  /** Serializes the event itself. */
  private final AuditEventMapper eventMapper;

  /** Adds the marker fields to the serialized event. */
  private final ObjectMapper objectMapper;

  /**
   * Constructor.
   *
   * @param eventMapper the mapper serializing the events
   * @param objectMapper the mapper adding the marker fields
   */
  public StdoutAuditEventRepository(@NonNull final AuditEventMapper eventMapper,
      @NonNull final ObjectMapper objectMapper) {
    this(eventMapper, objectMapper, null);
  }

  /**
   * Constructor taking an event filter.
   *
   * @param eventMapper the mapper serializing the events
   * @param objectMapper the mapper adding the marker fields
   * @param filter the filter deciding which events are written, or {@code null} for all of them
   */
  public StdoutAuditEventRepository(@NonNull final AuditEventMapper eventMapper,
      @NonNull final ObjectMapper objectMapper, @Nullable final Predicate<AuditEvent> filter) {
    super(filter != null ? filter : e -> true);
    this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  /** {@inheritDoc} */
  @Override
  protected void addEvent(@NonNull final AuditEvent event) {
    auditLogger.info(this.toJsonLine(event));
  }

  /**
   * Serializes the event and adds the marker fields.
   *
   * @param event the event
   * @return one line of JSON
   */
  private String toJsonLine(final AuditEvent event) {
    final ObjectNode node = (ObjectNode) this.objectMapper.readTree(this.eventMapper.write(event));
    node.put(LOG_TYPE_FIELD, LOG_TYPE_VALUE);
    node.put(SENSITIVE_FIELD, true);
    node.put(LOGGER_FIELD, AUDIT_LOGGER_NAME);
    return this.objectMapper.writeValueAsString(node);
  }

  /**
   * Always {@code false}. Once written, the events are out of the application's reach.
   */
  @Override
  public boolean supportsFind() {
    return false;
  }

  /** {@inheritDoc} */
  @Override
  protected Iterator<AuditEvent> getEvents() throws UnsupportedOperationException {
    throw new UnsupportedOperationException("The events written to standard output cannot be read back");
  }

}
