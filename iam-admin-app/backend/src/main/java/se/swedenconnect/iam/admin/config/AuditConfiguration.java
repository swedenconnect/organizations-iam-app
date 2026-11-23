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

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import se.swedenconnect.iam.admin.audit.CorrelationIdFilter;
import se.swedenconnect.iam.admin.audit.StdoutAuditEventRepository;
import se.swedenconnect.spring.audit.autoconfigure.AuditRepositoryProperties;
import se.swedenconnect.spring.audit.repository.AbstractAuditEventRepository;
import se.swedenconnect.spring.audit.repository.AuditEventMapper;
import se.swedenconnect.spring.audit.repository.DelegatingAuditEventRepository;
import se.swedenconnect.spring.audit.repository.InMemoryAuditEventRepository;
import se.swedenconnect.spring.audit.repository.JsonAuditEventMapper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Configuration for audit logging.
 *
 * <p>The audit event chain is set up by the audit support starter from the {@code audit.*}
 * properties, and the listener writing the events to the repository by the actuator. What is added
 * here is the filter that gives every request in a session its correlation ID, and, when
 * {@code iam.admin.audit.stdout.enabled} is set, the repository that writes the events to standard
 * output.</p>
 *
 * @author Martin Lindström
 * @author David Goldring
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditRepositoryProperties.class)
public class AuditConfiguration {

  /**
   * Registers the {@link CorrelationIdFilter} ahead of the Spring Security filter chain.
   *
   * @return the filter registration
   */
  @Bean
  FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
    final FilterRegistrationBean<CorrelationIdFilter> registration =
        new FilterRegistrationBean<>(new CorrelationIdFilter());
    registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);
    return registration;
  }

  /**
   * Writes the audit events to standard output, and keeps a bounded number of them in memory so that the actuator's
   * {@code auditevents} endpoint still has something to serve.
   *
   * <p>Supplying this bean is what makes {@code AuditRepositoryAutoConfiguration} of the audit support starter back
   * off, since it is conditional on no {@link AuditEventRepository} being present. The library's own backends are
   * therefore not configured while this is enabled, which is why the event filter and the write failure policy are
   * read from {@link AuditRepositoryProperties} here and applied by hand - the {@code audit.repository.*} properties
   * keep meaning what the library documents.</p>
   *
   * @param properties the application's properties, for the standard output settings
   * @param repositoryProperties the library's repository properties, for the filter and the failure policy
   * @param objectMapper the application's object mapper, if one is defined
   * @return the repository
   */
  @Bean
  @ConditionalOnProperty(prefix = "iam.admin.audit.stdout", name = "enabled", havingValue = "true")
  AuditEventRepository stdoutAuditEventRepository(final IamAdminProperties properties,
      final AuditRepositoryProperties repositoryProperties, final ObjectProvider<ObjectMapper> objectMapper) {

    final ObjectMapper mapper = objectMapper.getIfAvailable(() -> JsonMapper.builder().build());
    final AuditEventMapper eventMapper = new JsonAuditEventMapper(mapper);

    // The filter is applied by the delegating repository, so the two it delegates to take none.
    final DelegatingAuditEventRepository repository = new DelegatingAuditEventRepository(
        List.of(new StdoutAuditEventRepository(eventMapper, mapper),
            new InMemoryAuditEventRepository(properties.getAudit().getStdout().getInMemoryCapacity())),
        AbstractAuditEventRepository.inclusionExclusionPredicate(
            repositoryProperties.getIncludeEvents(), repositoryProperties.getExcludeEvents()));

    if (repositoryProperties.getThrowOnWriteFail() != null) {
      repository.setThrowOnWriteFail(repositoryProperties.getThrowOnWriteFail());
    }
    return repository;
  }

}
