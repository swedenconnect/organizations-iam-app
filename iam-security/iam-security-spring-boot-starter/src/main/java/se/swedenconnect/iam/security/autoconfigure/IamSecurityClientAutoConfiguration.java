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
package se.swedenconnect.iam.security.autoconfigure;

import com.nimbusds.jose.jwk.JWK;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.NimbusJwtClientAuthenticationParametersConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import se.swedenconnect.iam.security.claims.OrgRightsClaimParser;
import se.swedenconnect.iam.security.client.OAuthClientContext;
import se.swedenconnect.iam.security.client.OidcTokenEndpointLoggingInterceptor;
import se.swedenconnect.iam.security.client.OrgRightsOidcUserService;
import se.swedenconnect.iam.security.client.ResourceParameterConverter;
import se.swedenconnect.security.credential.PkiCredential;
import se.swedenconnect.security.credential.config.properties.PkiCredentialConfigurationProperties;
import se.swedenconnect.security.credential.factory.PkiCredentialFactory;
import se.swedenconnect.security.credential.nimbus.JwkTransformerFunction;
import se.swedenconnect.security.credential.spring.autoconfigure.SpringCredentialBundlesAutoConfiguration;

/**
 * Auto-configuration for OIDC and OAuth2 clients.
 *
 * <p>Activated when {@code spring-security-oauth2-client} is on the classpath.</p>
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>{@link OrgRightsOidcUserService} — parses the {@code org_rights} claim and populates
 *       organizational authorities on the OIDC user</li>
 *   <li>{@link JWK oidcClientJwk} — loaded from the configured credential properties
 *       (bundle, JKS, or PEM style) via {@link JwkTransformerFunction}; throws at startup
 *       if {@code iam.security.client.credential} is not set</li>
 *   <li>{@link NimbusJwtClientAuthenticationParametersConverter} for
 *       {@code authorization_code}, {@code refresh_token}, and {@code client_credentials}
 *       grant types — all conditional on the {@link JWK} bean being present</li>
 *   <li>{@link RestClientRefreshTokenTokenResponseClient} — pre-configured with
 *       {@code private_key_jwt} authentication so that expired access tokens can be
 *       refreshed transparently</li>
 *   <li>{@link OAuthClientContext} — session-scoped context holding the current org and function</li>
 *   <li>{@link OAuth2AuthorizedClientManager} — a {@link DefaultOAuth2AuthorizedClientManager}
 *       that is request-bound and can redirect the browser to Keycloak when no token is cached.
 *       Scope placeholder resolution and {@code resource} parameter injection are handled by
 *       {@link se.swedenconnect.iam.security.client.OrgScopedAuthorizationRequestResolver} at
 *       authorization request build time.</li>
 * </ul>
 *
 * <p>Note: this configuration provides the converter beans but does not assemble full token
 * response clients. Applications wire the converters onto their own token response client beans,
 * allowing app-specific configuration such as debug logging interceptors.</p>
 *
 * @author Martin Lindström
 */
@AutoConfiguration(after = SpringCredentialBundlesAutoConfiguration.class)
@ConditionalOnClass(ClientRegistration.class)
@Import(OAuthClientContext.class)
public class IamSecurityClientAutoConfiguration {

  /**
   * Provides a default {@link OrgRightsOidcUserService} bean.
   *
   * <p>Applications may define their own {@link OrgRightsOidcUserService} bean to override this default,
   * for example to add admin-only enforcement or SSO session awareness.</p>
   *
   * @param claimParser the claim parser; must not be null
   * @return a new {@link OrgRightsOidcUserService}; never null
   */
  @Bean
  @ConditionalOnMissingBean
  OrgRightsOidcUserService orgRightsOidcUserService(
      final OrgRightsClaimParser claimParser,
      final IamSecurityProperties properties) {
    return new OrgRightsOidcUserService(claimParser, properties.getFunction());
  }

  /**
   * Loads the OIDC client credential and derives a {@link JWK} from it using
   * {@link JwkTransformerFunction}.
   *
   * <p>Requires {@code iam.security.client.credential} to be configured — throws
   * {@link IllegalStateException} at startup if it is not.</p>
   *
   * @param properties the IAM security properties; must not be null
   * @param pkiCredentialFactory the credential factory; must not be null
   * @return the JWK containing both public and private key material; never null
   * @throws Exception if credential loading fails
   */
  @Bean
  @ConditionalOnMissingBean(JWK.class)
  JWK oidcClientJwk(
      final IamSecurityProperties properties,
      final PkiCredentialFactory pkiCredentialFactory) throws Exception {

    final PkiCredentialConfigurationProperties credentialProps = properties.getClient().getCredential();
    if (credentialProps == null) {
      throw new IllegalStateException(
          "iam.security.client.credential is not configured — cannot create OIDC client JWK");
    }
    final PkiCredential credential = pkiCredentialFactory.createCredential(credentialProps);
    return JwkTransformerFunction.function().apply(credential);
  }

  /**
   * Provides a {@link NimbusJwtClientAuthenticationParametersConverter} for the
   * {@code authorization_code} grant type.
   *
   * <p>Only registered when a {@link JWK} bean is present.</p>
   *
   * @param jwk the JWK to use for signing client assertions; must not be null
   * @return the parameters converter; never null
   */
  @Bean
  @ConditionalOnMissingBean(name = "authCodeJwtConverter")
  NimbusJwtClientAuthenticationParametersConverter<OAuth2AuthorizationCodeGrantRequest> authCodeJwtConverter(
      final JWK jwk) {
    return new NimbusJwtClientAuthenticationParametersConverter<>(clientRegistration -> jwk);
  }

  @Bean
  @ConditionalOnMissingBean(name = "clientCredentialsJwtConverter")
  NimbusJwtClientAuthenticationParametersConverter<OAuth2ClientCredentialsGrantRequest> clientCredentialsJwtConverter(
      final JWK jwk) {
    return new NimbusJwtClientAuthenticationParametersConverter<>(clientRegistration -> jwk);
  }

  @Bean
  @ConditionalOnMissingBean(name = "refreshTokenJwtConverter")
  NimbusJwtClientAuthenticationParametersConverter<OAuth2RefreshTokenGrantRequest> refreshTokenJwtConverter(
      final JWK jwk) {
    return new NimbusJwtClientAuthenticationParametersConverter<>(clientRegistration -> jwk);
  }

  /**
   * Provides a default {@link RestClientRefreshTokenTokenResponseClient} configured with
   * {@code private_key_jwt} authentication.
   *
   * <p>Without this bean, the default refresh token client only supports
   * {@code client_secret_basic}, {@code client_secret_post}, and {@code none}. Since all
   * clients in this project use {@code private_key_jwt}, the refresh grant would fail when
   * Spring Security attempts to refresh an expired access token.</p>
   *
   * @param refreshTokenJwtConverter the JWT converter for the refresh token grant; must not be null
   * @return the configured client; never null
   */
  @Bean
  @ConditionalOnMissingBean
  RestClientRefreshTokenTokenResponseClient refreshTokenTokenResponseClient(
      final NimbusJwtClientAuthenticationParametersConverter<OAuth2RefreshTokenGrantRequest>
          refreshTokenJwtConverter) {
    final var client = new RestClientRefreshTokenTokenResponseClient();
    client.addParametersConverter(refreshTokenJwtConverter);
    return client;
  }

  /**
   * Provides a default {@link OAuth2AuthorizedClientManager} based on
   * {@link DefaultOAuth2AuthorizedClientManager}.
   *
   * <p>{@link DefaultOAuth2AuthorizedClientManager} is request-bound and can redirect the
   * browser to Keycloak when no token is cached, making it suitable for interactive user
   * flows with {@code OAuth2ClientHttpRequestInterceptor}. Scope placeholder resolution
   * and {@code resource} parameter injection are handled upstream by
   * {@link se.swedenconnect.iam.security.client.OrgScopedAuthorizationRequestResolver} at
   * authorization request build time — the manager requires no custom
   * {@code contextAttributesMapper} for these concerns.</p>
   *
   * <p>Applications that make only background service-account calls and do not need browser
   * redirects should define their own {@link OAuth2AuthorizedClientManager} bean using
   * {@code AuthorizedClientServiceOAuth2AuthorizedClientManager} with only the
   * {@code clientCredentials} provider. Defining their own bean suppresses this one via
   * {@code @ConditionalOnMissingBean}.</p>
   *
   * @param clientRegistrationRepository the client registration repository; must not be null
   * @param authorizedClientRepository the authorized client repository; must not be null
   * @return the configured manager; never null
   */
  @Bean
  @ConditionalOnMissingBean(OAuth2AuthorizedClientManager.class)
  @ConditionalOnBean(OAuth2AuthorizedClientRepository.class)
  OAuth2AuthorizedClientManager authorizedClientManager(
      final ClientRegistrationRepository clientRegistrationRepository,
      final OAuth2AuthorizedClientRepository authorizedClientRepository,
      final RestClientRefreshTokenTokenResponseClient refreshTokenTokenResponseClient) {

    final DefaultOAuth2AuthorizedClientManager manager =
        new DefaultOAuth2AuthorizedClientManager(
            clientRegistrationRepository, authorizedClientRepository);

    manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
        .authorizationCode()
        .refreshToken(configurer -> configurer
            .accessTokenResponseClient(refreshTokenTokenResponseClient))
        .clientCredentials()
        .build());

    return manager;
  }

  /**
   * Provides a {@link ResourceParameterConverter} bean that adds the OAuth2 {@code resource}
   * parameter (RFC 8707) to authorization code token requests.
   *
   * <p>Applications wire this onto their {@link org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient}
   * via {@code addParametersConverter()}.</p>
   *
   * @param properties the IAM security properties; must not be null
   * @return the converter; never null
   */
  @Bean
  @ConditionalOnMissingBean
  ResourceParameterConverter resourceParameterConverter(final IamSecurityProperties properties) {
    return new ResourceParameterConverter(properties);
  }

  /**
   * Provides an {@link OidcTokenEndpointLoggingInterceptor} bean when
   * {@code iam.security.debug=true}.
   *
   * <p>Applications add this interceptor to their token endpoint {@link org.springframework.web.client.RestClient}
   * to enable trace-level logging of OAuth2/OIDC token requests and responses.
   * Never enable in production.</p>
   *
   * @return the logging interceptor; never null
   */
  @Bean
  @ConditionalOnProperty(name = "iam.security.debug", havingValue = "true")
  OidcTokenEndpointLoggingInterceptor oidcTokenEndpointLoggingInterceptor() {
    return new OidcTokenEndpointLoggingInterceptor();
  }

}
