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
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import se.swedenconnect.iam.security.autoconfigure.IamSecurityProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link OAuth2AuthorizationRequestResolver} that resolves {@code {org}} and
 * {@code {function}} scope placeholders in OAuth2 client registrations before the
 * authorization request is sent to Keycloak.
 *
 * <p>When a client registration uses a placeholder scope such as
 * {@code {org}:{function}:read}, the raw placeholder would otherwise be sent to
 * Keycloak verbatim during the browser-initiated authorization code flow — because
 * {@link org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver}
 * reads the scope directly from the {@link org.springframework.security.oauth2.client.registration.ClientRegistration}
 * without consulting the {@code contextAttributesMapper} on the authorized client manager.</p>
 *
 * <p>This resolver wraps any delegate resolver and post-processes the resulting
 * {@link OAuth2AuthorizationRequest}: if the scope contains {@code {org}} or
 * {@code {function}} placeholders, they are resolved from {@link OAuthClientContext}
 * (with fallback to {@code iam.security.function} for single-function applications).
 * The {@code resource} parameter (RFC 8707) is also injected if configured under
 * {@code iam.security.client.registrations.{registrationId}.resource}.</p>
 *
 * <p>This resolver is not auto-configured. Applications wire it into the
 * {@code SecurityFilterChain} as the delegate of
 * {@link PromptLoginAuthorizationRequestResolver}, or directly into
 * {@code oauth2Login} if {@code prompt=login} is not needed.</p>
 *
 * @author Martin Lindström
 */
@Slf4j
public class OrgScopedAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

  private final @NonNull OAuth2AuthorizationRequestResolver delegate;
  private final @NonNull OAuthClientContext oAuthClientContext;
  private final @NonNull IamSecurityProperties properties;

  /**
   * Creates a new resolver.
   *
   * @param delegate the delegate resolver that builds the base authorization request;
   *     must not be null
   * @param oAuthClientContext the session-scoped OAuth2 client context; must not be null
   * @param properties the IAM security properties; must not be null
   */
  public OrgScopedAuthorizationRequestResolver(
      final @NonNull OAuth2AuthorizationRequestResolver delegate,
      final @NonNull OAuthClientContext oAuthClientContext,
      final @NonNull IamSecurityProperties properties) {
    this.delegate = delegate;
    this.oAuthClientContext = oAuthClientContext;
    this.properties = properties;
  }

  @Override
  public @Nullable OAuth2AuthorizationRequest resolve(final @NonNull HttpServletRequest request) {
    final OAuth2AuthorizationRequest authRequest = this.delegate.resolve(request);
    if (authRequest == null) {
      return null;
    }
    final String requestUri = request.getRequestURI();
    final String registrationId = requestUri.substring(requestUri.lastIndexOf('/') + 1);
    return this.customize(authRequest, registrationId);
  }

  @Override
  public @Nullable OAuth2AuthorizationRequest resolve(
      final @NonNull HttpServletRequest request,
      final @NonNull String clientRegistrationId) {
    final OAuth2AuthorizationRequest authRequest = this.delegate.resolve(request, clientRegistrationId);
    if (authRequest == null) {
      return null;
    }
    return this.customize(authRequest, clientRegistrationId);
  }

  /**
   * Post-processes the authorization request by resolving scope placeholders and
   * injecting the {@code resource} parameter.
   *
   * @param authRequest the authorization request built by the delegate; must not be null
   * @param registrationId the client registration ID; must not be null
   * @return the customized authorization request; never null
   */
  private @NonNull OAuth2AuthorizationRequest customize(
      final @NonNull OAuth2AuthorizationRequest authRequest,
      final @NonNull String registrationId) {

    final OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.from(authRequest);
    boolean modified = false;

    // Resolve scope placeholders
    final String rawScope = String.join(" ", authRequest.getScopes());
    if (rawScope.contains("{org}") || rawScope.contains("{function}")) {
      final String org = this.oAuthClientContext.getOrg();
      final String func = Optional.ofNullable(this.oAuthClientContext.getFunction())
          .orElse(this.properties.getFunction());

      if (org == null || func == null) {
        log.warn("OrgScopedAuthorizationRequestResolver: scope '{}' contains placeholders "
            + "but OAuthClientContext has no org/function set — sending unresolved scope",
            rawScope);
      } else {
        final String resolvedScope = rawScope
            .replace("{org}", org)
            .replace("{function}", func);
        log.debug("Resolved scope '{}' -> '{}' for registration '{}'",
            rawScope, resolvedScope, registrationId);
        builder.scopes(Set.of(resolvedScope.split(" ")));
        modified = true;
      }
    }

    // Inject resource parameter
    final IamSecurityProperties.Client.RegistrationProperties regProps =
        this.properties.getClient().getRegistrations().get(registrationId);
    if (regProps != null && regProps.getResource() != null) {
      final Map<String, Object> params =
          new LinkedHashMap<>(authRequest.getAdditionalParameters());
      params.put("resource", regProps.getResource());
      builder.additionalParameters(params);
      modified = true;
    }

    return modified ? builder.build() : authRequest;
  }

}
