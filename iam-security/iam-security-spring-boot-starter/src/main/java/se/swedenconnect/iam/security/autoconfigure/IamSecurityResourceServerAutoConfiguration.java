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
package se.swedenconnect.iam.security.autoconfigure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import se.swedenconnect.iam.security.server.OrgRightsScopeConverter;

/**
 * Auto-configuration for OAuth2 resource servers.
 *
 * <p>Activated when {@code spring-security-oauth2-resource-server} is on the classpath
 * (detected via {@link JwtAuthenticationConverter}).</p>
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>{@link OrgRightsScopeConverter} — a {@link JwtAuthenticationConverter} that reads
 *       {@code {orgIdentifier}:{functionId}:{right}} entries from the access token {@code scope}
 *       claim and produces {@link se.swedenconnect.iam.security.claims.OrganizationalAuthority}
 *       granted authorities</li>
 * </ul>
 *
 * <p>Wire into a {@code SecurityFilterChain}:</p>
 * <pre>{@code
 * .oauth2ResourceServer(rs -> rs
 *     .jwt(jwt -> jwt.jwtAuthenticationConverter(orgRightsScopeConverter)))
 * }</pre>
 *
 * @author Martin Lindström
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(JwtAuthenticationConverter.class)
public class IamSecurityResourceServerAutoConfiguration {

  /**
   * Provides a default {@link OrgRightsScopeConverter} bean.
   *
   * <p>Applications may define their own {@link OrgRightsScopeConverter} or
   * {@link JwtAuthenticationConverter} bean to override this default.</p>
   *
   * @return a new {@link OrgRightsScopeConverter}; never null
   */
  @Bean
  @ConditionalOnMissingBean
  OrgRightsScopeConverter orgRightsScopeConverter() {
    return new OrgRightsScopeConverter();
  }

}
