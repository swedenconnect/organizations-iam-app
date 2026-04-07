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
package se.swedenconnect.iam.security.client;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom {@link OAuth2AuthorizationRequestResolver} that adds {@code prompt=login} to
 * Keycloak authorization requests for a specific registration, forcing the user to
 * re-authenticate at Keycloak even if an active SSO session exists.
 *
 * <p>{@code prompt=login} is only added when the authorization request's registration ID
 * matches the ID passed to the constructor. Requests for other registrations (e.g. OAuth2
 * API client registrations) pass through unmodified, allowing Keycloak to reuse the
 * existing session for those flows.</p>
 *
 * <p>This resolver is <strong>not</strong> auto-configured. When org-scoped token flows
 * are also needed, chain it with {@link OrgScopedAuthorizationRequestResolver}:
 *
 * <pre>{@code
 * @Bean
 * OrgScopedAuthorizationRequestResolver orgScopedResolver(
 *     ClientRegistrationRepository repo,
 *     OAuthClientContext ctx,
 *     IamSecurityProperties props) {
 *   return new OrgScopedAuthorizationRequestResolver(
 *       new DefaultOAuth2AuthorizationRequestResolver(repo, "/oauth2/authorization"),
 *       ctx, props);
 * }
 *
 * @Bean
 * OAuth2AuthorizationRequestResolver authorizationRequestResolver(
 *     OrgScopedAuthorizationRequestResolver orgScopedResolver) {
 *   return new PromptLoginAuthorizationRequestResolver(orgScopedResolver, "my-oidc-registration");
 * }
 * }</pre>
 *
 * @author Martin Lindström
 */
@Slf4j
public class PromptLoginAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

  private final @NonNull OAuth2AuthorizationRequestResolver delegate;
  private final @NonNull String registrationId;

  /**
   * Creates a new resolver with an explicit delegate. Use this constructor when the
   * delegate itself wraps another resolver, for example when chaining with
   * {@link OrgScopedAuthorizationRequestResolver}.
   *
   * @param delegate the delegate resolver; must not be null
   * @param registrationId the registration ID for which {@code prompt=login} is added;
   *     must not be null
   */
  public PromptLoginAuthorizationRequestResolver(
      final @NonNull OAuth2AuthorizationRequestResolver delegate,
      final @NonNull String registrationId) {
    this.delegate = delegate;
    this.registrationId = registrationId;
  }

  /**
   * Creates a new resolver using a {@link DefaultOAuth2AuthorizationRequestResolver}
   * built from the provided {@link ClientRegistrationRepository}. Use this constructor
   * when no additional resolver chaining is needed.
   *
   * @param clientRegistrationRepository the client registration repository; must not be null
   * @param registrationId the registration ID for which {@code prompt=login} is added;
   *     must not be null
   */
  public PromptLoginAuthorizationRequestResolver(
      final @NonNull ClientRegistrationRepository clientRegistrationRepository,
      final @NonNull String registrationId) {
    this(new DefaultOAuth2AuthorizationRequestResolver(
        clientRegistrationRepository, "/oauth2/authorization"), registrationId);
  }

  @Override
  public @Nullable OAuth2AuthorizationRequest resolve(final @NonNull HttpServletRequest request) {
    final OAuth2AuthorizationRequest authRequest = this.delegate.resolve(request);
    if (authRequest == null) {
      return null;
    }
    final String requestUri = request.getRequestURI();
    final String resolvedId = requestUri.substring(requestUri.lastIndexOf('/') + 1);
    if (!this.registrationId.equals(resolvedId)) {
      log.debug("Skipping prompt=login for registration '{}'", resolvedId);
      return authRequest;
    }
    return this.addPromptLogin(authRequest);
  }

  @Override
  public @Nullable OAuth2AuthorizationRequest resolve(
      final @NonNull HttpServletRequest request,
      final @NonNull String clientRegistrationId) {
    final OAuth2AuthorizationRequest authRequest = this.delegate.resolve(request, clientRegistrationId);
    if (authRequest == null) {
      return null;
    }
    if (!this.registrationId.equals(clientRegistrationId)) {
      log.debug("Skipping prompt=login for registration '{}'", clientRegistrationId);
      return authRequest;
    }
    return this.addPromptLogin(authRequest);
  }

  private @NonNull OAuth2AuthorizationRequest addPromptLogin(
      final @NonNull OAuth2AuthorizationRequest authRequest) {
    log.debug("Adding prompt=login to authorization request for registration '{}'", this.registrationId);
    final Map<String, Object> params =
        new LinkedHashMap<>(authRequest.getAdditionalParameters());
    params.put("prompt", "login");
    return OAuth2AuthorizationRequest.from(authRequest)
        .additionalParameters(params)
        .build();
  }

}
