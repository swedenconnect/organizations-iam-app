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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PromptLoginAuthorizationRequestResolver}.
 *
 * @author Martin Lindström
 */
class PromptLoginAuthorizationRequestResolverTest {

  private static final String REGISTRATION_ID = "my-registration";

  private final OAuth2AuthorizationRequestResolver delegate = mock(OAuth2AuthorizationRequestResolver.class);

  private final PromptLoginAuthorizationRequestResolver resolver =
      new PromptLoginAuthorizationRequestResolver(this.delegate, REGISTRATION_ID);

  private static OAuth2AuthorizationRequest baseRequest() {
    return OAuth2AuthorizationRequest.authorizationCode()
        .clientId("my-client")
        .authorizationUri("https://keycloak/auth")
        .redirectUri("https://my-app/callback")
        .build();
  }

  @Test
  void matchingRegistration_addsPromptLogin() {
    final HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/oauth2/authorization/my-registration");
    when(this.delegate.resolve(request)).thenReturn(baseRequest());

    final OAuth2AuthorizationRequest result = this.resolver.resolve(request);

    assertThat(result).isNotNull();
    assertThat(result.getAdditionalParameters()).containsEntry("prompt", "login");
  }

  @Test
  void nonMatchingRegistration_passesThrough() {
    final HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/oauth2/authorization/other-registration");
    when(this.delegate.resolve(request)).thenReturn(baseRequest());

    final OAuth2AuthorizationRequest result = this.resolver.resolve(request);

    assertThat(result).isNotNull();
    assertThat(result.getAdditionalParameters()).doesNotContainKey("prompt");
  }

  @Test
  void delegateReturnsNull_returnsNull() {
    final HttpServletRequest request = mock(HttpServletRequest.class);
    when(this.delegate.resolve(request)).thenReturn(null);

    final OAuth2AuthorizationRequest result = this.resolver.resolve(request);

    assertThat(result).isNull();
  }

  @Test
  void resolveByRegistrationId_matchingId_addsPromptLogin() {
    final HttpServletRequest request = mock(HttpServletRequest.class);
    when(this.delegate.resolve(any(), eq(REGISTRATION_ID))).thenReturn(baseRequest());

    final OAuth2AuthorizationRequest result = this.resolver.resolve(request, REGISTRATION_ID);

    assertThat(result).isNotNull();
    assertThat(result.getAdditionalParameters()).containsEntry("prompt", "login");
  }

  @Test
  void resolveByRegistrationId_nonMatchingId_passesThrough() {
    final HttpServletRequest request = mock(HttpServletRequest.class);
    when(this.delegate.resolve(any(), eq("other"))).thenReturn(baseRequest());

    final OAuth2AuthorizationRequest result = this.resolver.resolve(request, "other");

    assertThat(result).isNotNull();
    assertThat(result.getAdditionalParameters()).doesNotContainKey("prompt");
  }

}
