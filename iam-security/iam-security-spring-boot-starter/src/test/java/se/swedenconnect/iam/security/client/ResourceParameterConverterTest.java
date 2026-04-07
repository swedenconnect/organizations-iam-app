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

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.util.MultiValueMap;
import se.swedenconnect.iam.security.autoconfigure.IamSecurityProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ResourceParameterConverter}.
 *
 * @author Martin Lindström
 */
class ResourceParameterConverterTest {

  @Test
  void resourceConfigured_addsParameter() {
    final IamSecurityProperties props = new IamSecurityProperties();
    final IamSecurityProperties.Client.RegistrationProperties regProps =
        new IamSecurityProperties.Client.RegistrationProperties();
    regProps.setResource("https://my-service.example.com");
    props.getClient().getRegistrations().put("my-read", regProps);

    final ResourceParameterConverter converter = new ResourceParameterConverter(props);

    final ClientRegistration clientReg = mock(ClientRegistration.class);
    when(clientReg.getRegistrationId()).thenReturn("my-read");
    final OAuth2AuthorizationCodeGrantRequest grantRequest = mock(OAuth2AuthorizationCodeGrantRequest.class);
    when(grantRequest.getClientRegistration()).thenReturn(clientReg);

    final MultiValueMap<String, String> result = converter.convert(grantRequest);

    assertThat(result).containsKey("resource");
    assertThat(result.getFirst("resource")).isEqualTo("https://my-service.example.com");
  }

  @Test
  void noResourceConfigured_returnsEmptyMap() {
    final IamSecurityProperties props = new IamSecurityProperties();
    final IamSecurityProperties.Client.RegistrationProperties regProps =
        new IamSecurityProperties.Client.RegistrationProperties();
    props.getClient().getRegistrations().put("my-read", regProps);

    final ResourceParameterConverter converter = new ResourceParameterConverter(props);

    final ClientRegistration clientReg = mock(ClientRegistration.class);
    when(clientReg.getRegistrationId()).thenReturn("my-read");
    final OAuth2AuthorizationCodeGrantRequest grantRequest = mock(OAuth2AuthorizationCodeGrantRequest.class);
    when(grantRequest.getClientRegistration()).thenReturn(clientReg);

    final MultiValueMap<String, String> result = converter.convert(grantRequest);

    assertThat(result).isEmpty();
  }

  @Test
  void unknownRegistration_returnsEmptyMap() {
    final IamSecurityProperties props = new IamSecurityProperties();
    final IamSecurityProperties.Client.RegistrationProperties regProps =
        new IamSecurityProperties.Client.RegistrationProperties();
    regProps.setResource("https://my-service.example.com");
    props.getClient().getRegistrations().put("my-read", regProps);

    final ResourceParameterConverter converter = new ResourceParameterConverter(props);

    final ClientRegistration clientReg = mock(ClientRegistration.class);
    when(clientReg.getRegistrationId()).thenReturn("unknown");
    final OAuth2AuthorizationCodeGrantRequest grantRequest = mock(OAuth2AuthorizationCodeGrantRequest.class);
    when(grantRequest.getClientRegistration()).thenReturn(clientReg);

    final MultiValueMap<String, String> result = converter.convert(grantRequest);

    assertThat(result).isEmpty();
  }

}
