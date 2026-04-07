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
package se.swedenconnect.iam.demo.app.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import se.swedenconnect.iam.security.autoconfigure.IamSecurityProperties;
import se.swedenconnect.iam.security.client.OAuthClientContext;
import se.swedenconnect.iam.security.client.OrgScopedAuthorizationRequestResolver;
import se.swedenconnect.iam.security.client.PromptLoginAuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

/**
 * Spring Security configuration for the Demo Application.
 *
 * @author Martin Lindström
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Slf4j
public class SecurityConfiguration {

  @Bean
  public OrgScopedAuthorizationRequestResolver orgScopedAuthorizationRequestResolver(
      final ClientRegistrationRepository clientRegistrationRepository,
      final OAuthClientContext oAuthClientContext,
      final IamSecurityProperties iamSecurityProperties) {
    return new OrgScopedAuthorizationRequestResolver(
        new DefaultOAuth2AuthorizationRequestResolver(
            clientRegistrationRepository, "/oauth2/authorization"),
        oAuthClientContext,
        iamSecurityProperties);
  }

  @Bean
  public OAuth2AuthorizationRequestResolver authorizationRequestResolver(
      final OrgScopedAuthorizationRequestResolver orgScopedResolver) {
    return new PromptLoginAuthorizationRequestResolver(orgScopedResolver, "demo");
  }

  @Bean
  public AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequestRepository() {
    return new HttpSessionOAuth2AuthorizationRequestRepository();
  }

  @Bean
  public SecurityFilterChain securityFilterChain(
      final HttpSecurity http,
      final OAuth2AuthorizationRequestResolver authorizationRequestResolver,
      final AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequestRepository,
      final RestClientAuthorizationCodeTokenResponseClient authCodeTokenClient,
      final OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService) throws Exception {

    final HttpSessionRequestCache requestCache = new HttpSessionRequestCache() {
      @Override
      public void saveRequest(
          final HttpServletRequest request, final @NonNull HttpServletResponse response) {
        final String uri = request.getRequestURI();
        if (uri != null && (uri.startsWith("/api/") || uri.startsWith("/callback/oauth2/"))) {
          return;
        }
        super.saveRequest(request, response);
      }
    };

    http
        .csrf(AbstractHttpConfigurer::disable)
        .requestCache(cache -> cache.requestCache(requestCache))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
            .requestMatchers("/error", "/error/**").permitAll()
            .requestMatchers("/oauth2/**", "/login/oauth2/**", "/callback/oauth2/**").permitAll()
            .requestMatchers("/actuator/**").permitAll()
            .requestMatchers("/jwks").permitAll()
            .anyRequest().authenticated()
        )
        .oauth2Login(oauth -> oauth
            .authorizationEndpoint(a -> a
                .authorizationRequestResolver(authorizationRequestResolver)
                .authorizationRequestRepository(authorizationRequestRepository))
            .redirectionEndpoint(r -> r
                .baseUri("/login/oauth2/code/demo"))
            .loginPage("/oauth2/authorization/demo")
            .defaultSuccessUrl("/", true)
            .failureUrl("/?loginError")
            .tokenEndpoint(t -> t.accessTokenResponseClient(authCodeTokenClient))
            .userInfoEndpoint(u -> u.oidcUserService(oidcUserService))
        )
        .oauth2Client(c -> c
            .authorizationCodeGrant(g -> g
                .authorizationRequestRepository(authorizationRequestRepository)
                .accessTokenResponseClient(authCodeTokenClient))
        )
        .logout(logout -> logout
            .logoutUrl("/logout")
            .logoutSuccessUrl("/")
        );

    return http.build();
  }

  @Bean
  public CommonsRequestLoggingFilter requestLoggingFilter() {
    final CommonsRequestLoggingFilter filter = new CommonsRequestLoggingFilter();
    filter.setIncludeQueryString(true);
    filter.setIncludeHeaders(true);
    filter.setIncludePayload(true);
    filter.setMaxPayloadLength(2000);
    filter.setAfterMessagePrefix("REQUEST: ");
    return filter;
  }

}
