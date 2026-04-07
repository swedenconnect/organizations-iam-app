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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for the IAM Service API ({@code /iam-api/**}).
 *
 * <p>This filter chain runs at {@code @Order(1)}, before the main OIDC-based filter chain.
 * It matches only {@code /iam-api/**} paths and configures them as a stateless OAuth2
 * resource server. JWT decoding, signature verification, issuer validation, and audience
 * validation are auto-configured by Spring Boot from
 * {@code spring.security.oauth2.resourceserver.jwt.*} properties.</p>
 *
 * <p>The {@link RealmRoleJwtAuthenticationConverter} extracts the {@code superuser} realm
 * role from the token's {@code realm_access.roles} claim and maps it to
 * {@code ROLE_SUPERUSER}.</p>
 *
 * @author Martin Lindström
 */
@Configuration
public class IamServiceSecurityConfiguration {

  @Bean
  @Order(1)
  public SecurityFilterChain iamServiceFilterChain(final HttpSecurity http) throws Exception {
    http
        .securityMatcher("/iam-api/**")
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session
            .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .anyRequest().authenticated()
        )
        .oauth2ResourceServer(rs -> rs
            .jwt(jwt -> jwt
                .jwtAuthenticationConverter(new RealmRoleJwtAuthenticationConverter())));

    return http.build();
  }

}
