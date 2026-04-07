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
package se.swedenconnect.iam.demo.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import se.swedenconnect.iam.security.server.OrgRightsScopeConverter;

/**
 * Spring Security configuration for the Demo Service resource server.
 *
 * <p>JWT decoding, signature verification, issuer validation, and audience validation are
 * auto-configured by Spring Boot from {@code spring.security.oauth2.resourceserver.jwt.*}
 * properties. The only application-specific wiring is the {@link OrgRightsScopeConverter}
 * which maps {@code {orgId}:{function}:{right}} scopes to granted authorities.</p>
 *
 * @author Martin Lindström
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {

  @Bean
  public SecurityFilterChain securityFilterChain(
      final HttpSecurity http,
      final OrgRightsScopeConverter orgRightsScopeConverter) throws Exception {

    http
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/**").permitAll()
            .anyRequest().authenticated()
        )
        .oauth2ResourceServer(rs -> rs
            .jwt(jwt -> jwt
                .jwtAuthenticationConverter(orgRightsScopeConverter)))
        .addFilterBefore(new JwtDebugLoggingFilter(), BearerTokenAuthenticationFilter.class);

    return http.build();
  }

}
