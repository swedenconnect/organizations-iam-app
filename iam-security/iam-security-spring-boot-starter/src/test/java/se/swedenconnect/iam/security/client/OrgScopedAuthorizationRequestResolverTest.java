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
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import se.swedenconnect.iam.security.autoconfigure.IamSecurityProperties;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrgScopedAuthorizationRequestResolver}.
 *
 * @author Martin Lindström
 */
class OrgScopedAuthorizationRequestResolverTest {

  private final OAuth2AuthorizationRequestResolver delegate = mock(OAuth2AuthorizationRequestResolver.class);
  private final OAuthClientContext clientContext = new OAuthClientContext();
  private final IamSecurityProperties properties = new IamSecurityProperties();

  private OrgScopedAuthorizationRequestResolver createResolver() {
    return new OrgScopedAuthorizationRequestResolver(this.delegate, this.clientContext, this.properties);
  }

  private static OAuth2AuthorizationRequest requestWithScopes(final String... scopes) {
    return OAuth2AuthorizationRequest.authorizationCode()
        .clientId("my-client")
        .authorizationUri("https://keycloak/auth")
        .redirectUri("https://my-app/callback")
        .scopes(Set.of(scopes))
        .build();
  }

  @Test
  void orgAndFunctionPlaceholders_resolved() {
    this.clientContext.setOrg("5590026042");
    this.clientContext.setFunction("demo");

    final HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/oauth2/authorization/demo-service-read");
    when(this.delegate.resolve(request)).thenReturn(requestWithScopes("{org}:{function}:read"));

    final OAuth2AuthorizationRequest result = this.createResolver().resolve(request);

    assertThat(result).isNotNull();
    assertThat(result.getScopes()).containsExactly("5590026042:demo:read");
  }

  @Test
  void functionFallsBackToProperty() {
    this.clientContext.setOrg("5590026042");
    this.properties.setFunction("demo");

    final HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/oauth2/authorization/demo-service-read");
    when(this.delegate.resolve(request)).thenReturn(requestWithScopes("{org}:{function}:read"));

    final OAuth2AuthorizationRequest result = this.createResolver().resolve(request);

    assertThat(result).isNotNull();
    assertThat(result.getScopes()).containsExactly("5590026042:demo:read");
  }

  @Test
  void noPlaceholders_passesThrough() {
    final HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/oauth2/authorization/demo-service-read");
    final OAuth2AuthorizationRequest baseRequest = requestWithScopes("openid", "profile");
    when(this.delegate.resolve(request)).thenReturn(baseRequest);

    final OAuth2AuthorizationRequest result = this.createResolver().resolve(request);

    assertThat(result).isNotNull();
    assertThat(result.getScopes()).containsExactlyInAnyOrder("openid", "profile");
  }

  @Test
  void resourceInjected() {
    final IamSecurityProperties.Client.RegistrationProperties regProps =
        new IamSecurityProperties.Client.RegistrationProperties();
    regProps.setResource("https://my-service.example.com");
    this.properties.getClient().getRegistrations().put("demo-service-read", regProps);

    final HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/oauth2/authorization/demo-service-read");
    when(this.delegate.resolve(request)).thenReturn(requestWithScopes("openid"));

    final OAuth2AuthorizationRequest result = this.createResolver().resolve(request);

    assertThat(result).isNotNull();
    assertThat(result.getAdditionalParameters()).containsEntry("resource", "https://my-service.example.com");
  }

  @Test
  void noOrgSet_warnsAndPassesUnresolved() {
    // No org set on clientContext
    final HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/oauth2/authorization/demo-service-read");
    when(this.delegate.resolve(request)).thenReturn(requestWithScopes("{org}:{function}:read"));

    final OAuth2AuthorizationRequest result = this.createResolver().resolve(request);

    assertThat(result).isNotNull();
    assertThat(result.getScopes()).containsExactly("{org}:{function}:read");
  }

  @Test
  void delegateReturnsNull_returnsNull() {
    final HttpServletRequest request = mock(HttpServletRequest.class);
    when(this.delegate.resolve(request)).thenReturn(null);

    final OAuth2AuthorizationRequest result = this.createResolver().resolve(request);

    assertThat(result).isNull();
  }

}
