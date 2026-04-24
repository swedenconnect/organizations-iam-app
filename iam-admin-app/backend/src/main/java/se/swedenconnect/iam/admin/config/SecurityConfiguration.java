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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.web.filter.CommonsRequestLoggingFilter;
import se.swedenconnect.iam.admin.auth.OrgRightsOidcUserService;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;

/**
 * Spring Security configuration for the IAM Admin application.
 *
 * <p>Two distinct login paths are supported:</p>
 * <ul>
 *   <li><strong>Standard login</strong> ({@code /oauth2/authorization/iam-admin}) – forces
 *       re-authentication at KeyCloak via {@code prompt=login}.</li>
 *   <li><strong>SSO login</strong> (configurable via {@code iam.admin.sso-login-path}) – reuses an
 *       existing KeyCloak session. Supports optional {@code org} and {@code func} query
 *       parameters to constrain and assert admin authorization.</li>
 * </ul>
 *
 * @author Martin Lindström
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Slf4j
public class SecurityConfiguration {

  @Value("${server.servlet.context-path:/}")
  private String contextPath;

  /**
   * Returns a context-path-prefixed path, e.g. "/admin" + "/index.html" → "/admin/index.html".
   * When the context path is "/", the prefix is empty to avoid double slashes.
   */
  private @NonNull String getPrefixedPath(final @NonNull String path) {
    final String prefix = "/".equals(this.contextPath) ? "" : this.contextPath;
    return prefix + path;
  }

  @Bean
  @Order(2)
  public SecurityFilterChain securityFilterChain(
      final HttpSecurity http,
      final RestClientAuthorizationCodeTokenResponseClient authCodeTokenClient,
      final OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService,
      final OAuth2AuthorizationRequestResolver authorizationRequestResolver,
      final AdminSessionBootstrapHandler successHandler,
      @Value("${iam.admin.sso-login-path:/sso/login}") final String ssoLoginPath) throws Exception {

    final HttpSessionRequestCache requestCache = new HttpSessionRequestCache() {
      @Override
      public void saveRequest(
          final HttpServletRequest request, final @NonNull HttpServletResponse response) {
        final String path = request.getServletPath();
        if (path != null && path.startsWith("/api/")) {
          return;
        }
        super.saveRequest(request, response);
      }
    };

    final AuthenticationFailureHandler failureHandler = this::handleAuthenticationFailure;

    http
        .csrf(AbstractHttpConfigurer::disable)
        .requestCache(cache -> cache.requestCache(requestCache))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
            .requestMatchers("/theme-init.js", "/theme/**").permitAll()
            .requestMatchers("/api/theme", "/api/theme/footer").permitAll()
            .requestMatchers("/error/**").permitAll()
            .requestMatchers("/api/auth-error").permitAll()
            .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
            .requestMatchers(ssoLoginPath).permitAll()
            .requestMatchers("/actuator/**").permitAll()
            .requestMatchers("/jwks").permitAll()
            .anyRequest().authenticated()
        )
        .oauth2Login(oauth -> oauth
            .loginPage("/oauth2/authorization/iam-admin")
            .authorizationEndpoint(a -> a
                .authorizationRequestResolver(authorizationRequestResolver))
            .successHandler(successHandler)
            .failureHandler(failureHandler)
            .tokenEndpoint(t -> t.accessTokenResponseClient(authCodeTokenClient))
            .userInfoEndpoint(u -> u.oidcUserService(oidcUserService))
        )
        .oauth2Client(c -> c
            .authorizationCodeGrant(g -> g.accessTokenResponseClient(authCodeTokenClient))
        )
        .logout(logout -> logout
            .logoutUrl("/logout")
            .logoutSuccessUrl(this.getPrefixedPath("/"))
        );

    return http.build();
  }

  private void handleAuthenticationFailure(
      final @NonNull HttpServletRequest request,
      final @NonNull HttpServletResponse response,
      final @NonNull Exception exception) throws java.io.IOException {

    final HttpSession session = request.getSession(false);
    final boolean ssoLogin = session != null
        && Boolean.TRUE.equals(session.getAttribute(OrgRightsOidcUserService.SSO_ACTIVE_ATTR));

    if (ssoLogin) {
      log.debug("SSO login failed — redirecting to login page with error");
    } else {
      log.debug("Standard login failed — redirecting to login page");
    }
    response.sendRedirect(this.getPrefixedPath("/?loginError"));
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
