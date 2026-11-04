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

import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import se.swedenconnect.iam.admin.audit.CorrelationIdFilter;

/**
 * Configuration for audit logging.
 *
 * <p>The audit event chain and the repository are set up by the audit support starter from the
 * {@code audit.*} properties, and the listener writing the events to the repository by the
 * actuator. What is added here is the filter that gives every request in a session its correlation
 * ID.</p>
 *
 * @author Martin Lindström
 */
@Configuration(proxyBeanMethods = false)
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

}
