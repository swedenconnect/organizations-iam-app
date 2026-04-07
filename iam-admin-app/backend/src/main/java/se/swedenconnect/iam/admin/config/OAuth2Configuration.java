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

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.NimbusJwtClientAuthenticationParametersConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;
import se.swedenconnect.iam.admin.auth.SsoAuthorizationRequestResolver;
import se.swedenconnect.iam.security.client.OidcTokenEndpointLoggingInterceptor;
import se.swedenconnect.iam.security.client.ResourceParameterConverter;

/**
 * Configuration for OAuth 2.0 and OIDC.
 *
 * @author Martin Lindström
 */
@Configuration
@Slf4j
public class OAuth2Configuration {

  @Bean
  OAuth2AuthorizationRequestResolver authorizationRequestResolver(
      final ClientRegistrationRepository clientRegistrationRepository) {
    return new SsoAuthorizationRequestResolver(clientRegistrationRepository, "iam-admin");
  }

  @Bean
  RestClientAuthorizationCodeTokenResponseClient authCodeTokenClient(
      final RestClient.Builder restClientBuilder,
      final ObjectProvider<OidcTokenEndpointLoggingInterceptor> loggingInterceptorProvider,
      final com.nimbusds.jose.jwk.JWK oidcClientJwk,
      final ResourceParameterConverter resourceParameterConverter) {

    loggingInterceptorProvider.ifAvailable(restClientBuilder::requestInterceptor);

    final RestClient restClient = restClientBuilder
        .messageConverters((messageConverters) -> {
          messageConverters.clear();
          messageConverters.add(new FormHttpMessageConverter());
          messageConverters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
        })
        .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
        .build();

    final var client = new RestClientAuthorizationCodeTokenResponseClient();
    client.setRestClient(restClient);
    client.addParametersConverter(
        new NimbusJwtClientAuthenticationParametersConverter<OAuth2AuthorizationCodeGrantRequest>(
            reg -> oidcClientJwk));
    client.addParametersConverter(resourceParameterConverter);

    return client;
  }

  @Bean
  RestClientClientCredentialsTokenResponseClient clientCredentialsTokenClient(
      final com.nimbusds.jose.jwk.JWK oidcClientJwk) {

    final var client = new RestClientClientCredentialsTokenResponseClient();
    client.addParametersConverter(
        new NimbusJwtClientAuthenticationParametersConverter<OAuth2ClientCredentialsGrantRequest>(
            reg -> oidcClientJwk));
    return client;
  }

  @Bean
  OAuth2AuthorizedClientManager authorizedClientManager(
      final ClientRegistrationRepository clientRegistrationRepository,
      final OAuth2AuthorizedClientService authorizedClientService,
      final RestClientClientCredentialsTokenResponseClient clientCredentialsTokenClient) {

    final var provider = OAuth2AuthorizedClientProviderBuilder.builder()
        .clientCredentials(c -> c.accessTokenResponseClient(clientCredentialsTokenClient))
        .build();

    final var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
        clientRegistrationRepository, authorizedClientService);
    manager.setAuthorizedClientProvider(provider);

    return manager;
  }

}
