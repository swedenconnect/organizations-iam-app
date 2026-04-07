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
package se.swedenconnect.iam.admin.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import se.swedenconnect.iam.security.client.PromptLoginAuthorizationRequestResolver;

/**
 * Custom {@link OAuth2AuthorizationRequestResolver} that controls whether {@code prompt=login} is added to the KeyCloak
 * authorization request.
 *
 * <p>For the <em>standard</em> login path ({@code /oauth2/authorization/{registrationId}}),
 * {@code prompt=login} is always added to force re-authentication at KeyCloak.</p>
 *
 * <p>For the <em>SSO</em> login path, the session attribute
 * {@value OrgRightsOidcUserService#SSO_ACTIVE_ATTR} is {@code true} (set by
 * {@link se.swedenconnect.iam.admin.controllers.SsoLoginController} before the redirect). In this case {@code prompt=login} is
 * omitted so that an existing KeyCloak SSO session is reused.</p>
 *
 * @author Martin Lindström
 */
@Slf4j
public class SsoAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

  private final DefaultOAuth2AuthorizationRequestResolver delegate;
  private final String registrationId;
  private final PromptLoginAuthorizationRequestResolver promptLoginResolver;

  public SsoAuthorizationRequestResolver(
      final @NonNull ClientRegistrationRepository clientRegistrationRepository,
      final @NonNull String registrationId) {
    this.delegate = new DefaultOAuth2AuthorizationRequestResolver(
        clientRegistrationRepository, "/oauth2/authorization");
    this.registrationId = registrationId;
    this.promptLoginResolver = new PromptLoginAuthorizationRequestResolver(
        clientRegistrationRepository, registrationId);
  }

  @Override
  public @Nullable OAuth2AuthorizationRequest resolve(final @NonNull HttpServletRequest request) {
    final OAuth2AuthorizationRequest authRequest = this.delegate.resolve(request);
    return this.customize(request, authRequest);
  }

  @Override
  public @Nullable OAuth2AuthorizationRequest resolve(
      final @NonNull HttpServletRequest request,
      final @NonNull String clientRegistrationId) {
    final OAuth2AuthorizationRequest authRequest =
        this.delegate.resolve(request, clientRegistrationId);
    return this.customize(request, authRequest);
  }

  private @Nullable OAuth2AuthorizationRequest customize(
      final @NonNull HttpServletRequest request,
      final @Nullable OAuth2AuthorizationRequest authRequest) {

    if (authRequest == null) {
      return null;
    }

    final HttpSession session = request.getSession(false);
    final boolean ssoLogin = session != null
        && Boolean.TRUE.equals(session.getAttribute(OrgRightsOidcUserService.SSO_ACTIVE_ATTR));

    if (ssoLogin) {
      log.debug("SSO login: omitting prompt=login, using existing KeyCloak session if available");
      return authRequest;
    }

    log.debug("Standard login: delegating to PromptLoginAuthorizationRequestResolver");
    return this.promptLoginResolver.resolve(request);
  }

}
