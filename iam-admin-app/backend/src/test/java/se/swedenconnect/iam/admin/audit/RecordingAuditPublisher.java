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
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import se.swedenconnect.spring.audit.AuditEvent;
import se.swedenconnect.spring.audit.DefaultAuditEventContextResolver;
import se.swedenconnect.spring.audit.repository.AuditEventWriteException;
import se.swedenconnect.spring.audit.support.ApplicationName;
import se.swedenconnect.spring.audit.transform.EventTransformer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * An {@link ApplicationEventPublisher} for tests that turns each audit application event into the
 * audit event the library would write, at the moment it is published, in the same way the audit
 * listener does. The correlation ID and principal are therefore resolved on the publishing thread.
 *
 * @author Martin Lindström
 */
public class RecordingAuditPublisher implements ApplicationEventPublisher {

  private final DefaultAuditEventContextResolver resolver;

  private final List<AuditEvent> events = new ArrayList<>();

  /** Audit types whose write fails, as a failing repository would make it. */
  private Predicate<String> failingTypes = t -> false;

  /**
   * Constructor.
   */
  public RecordingAuditPublisher() {
    this.resolver = new DefaultAuditEventContextResolver(new ApplicationName("iam-admin"), null);
    this.resolver.setDefaultPrincipal(AuditEvent.SYSTEM_PRINCIPAL);
  }

  /**
   * Makes the write of the given audit types fail.
   *
   * @param types the audit types
   */
  public void failWritesOf(final String... types) {
    final Set<String> failing = Set.of(types);
    this.failingTypes = failing::contains;
  }

  /** {@inheritDoc} */
  @Override
  public void publishEvent(final @NonNull Object event) {
    if (event instanceof final ApplicationEvent applicationEvent
        && event instanceof final EventTransformer transformer
        && transformer.supports(applicationEvent)) {
      final AuditEvent auditEvent = (AuditEvent) transformer.transform(applicationEvent, this.resolver.getContext(event));
      if (this.failingTypes.test(auditEvent.getType())) {
        throw new AuditEventWriteException("Simulated write failure for " + auditEvent.getType());
      }
      this.events.add(auditEvent);
    }
  }

  /**
   * Returns the audit events written so far.
   *
   * @return the audit events
   */
  public List<AuditEvent> events() {
    return this.events;
  }

  /**
   * Returns the types of the audit events written so far, in order.
   *
   * @return the types
   */
  public List<String> types() {
    return this.events.stream().map(AuditEvent::getType).toList();
  }

  /**
   * Returns the only audit event of the given type.
   *
   * @param type the type
   * @return the event
   * @throws AssertionError if there is not exactly one
   */
  public AuditEvent single(final String type) {
    final List<AuditEvent> matching = this.events.stream().filter(e -> type.equals(e.getType())).toList();
    if (matching.size() != 1) {
      throw new AssertionError("Expected one '%s' event, got %d: %s".formatted(type, matching.size(), this.types()));
    }
    return matching.getFirst();
  }

  /**
   * Returns the audit events of the given type.
   *
   * @param type the type
   * @return the events
   */
  public List<AuditEvent> all(final String type) {
    return this.events.stream().filter(e -> type.equals(e.getType())).toList();
  }

  /**
   * Returns a member of an object in the event's data.
   *
   * @param event the event
   * @param object the name of the object
   * @return the object's members
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(final AuditEvent event, final String object) {
    return (Map<String, Object>) event.getData().get(object);
  }

  /**
   * Clears the recorded events.
   */
  public void clear() {
    this.events.clear();
  }

}
