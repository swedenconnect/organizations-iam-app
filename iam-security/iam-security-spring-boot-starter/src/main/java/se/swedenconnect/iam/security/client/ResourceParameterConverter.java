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

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import se.swedenconnect.iam.security.autoconfigure.IamSecurityProperties;

/**
 * A parameters converter that adds the OAuth2 {@code resource} parameter (RFC 8707) to
 * authorization code token requests.
 *
 * <p>Reads the {@code resource} value from
 * {@code iam.security.client.registrations.{registrationId}.resource}. If no resource is
 * configured for the registration, the converter returns an empty map.</p>
 *
 * <p>Add this converter to the {@link org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient}
 * via {@code addParametersConverter()} alongside the {@code private_key_jwt} converter.</p>
 *
 * @author Martin Lindström
 */
@Slf4j
public class ResourceParameterConverter
    implements Converter<OAuth2AuthorizationCodeGrantRequest, MultiValueMap<String, String>> {

  private final IamSecurityProperties properties;

  public ResourceParameterConverter(final @NonNull IamSecurityProperties properties) {
    this.properties = properties;
  }

  @Override
  public @NonNull MultiValueMap<String, String> convert(
      final @NonNull OAuth2AuthorizationCodeGrantRequest grantRequest) {

    final MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();

    final String registrationId = grantRequest.getClientRegistration().getRegistrationId();
    final IamSecurityProperties.Client.RegistrationProperties regProps =
        this.properties.getClient().getRegistrations().get(registrationId);

    if (regProps != null && regProps.getResource() != null) {
      parameters.add("resource", regProps.getResource());
      log.debug("ResourceParameterConverter: adding resource={} for registration '{}'",
          regProps.getResource(), registrationId);
    }
    else {
      log.debug("ResourceParameterConverter: no resource configured for registration '{}'",
          registrationId);
    }

    return parameters;
  }

}
