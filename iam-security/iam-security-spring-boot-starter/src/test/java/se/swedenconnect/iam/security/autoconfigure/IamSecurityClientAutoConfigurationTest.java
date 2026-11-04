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
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.util.ReflectionTestUtils;
import se.swedenconnect.iam.security.client.OrgKeyedAuthorizedClientRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for the authorized client repository and manager provided by
 * {@link IamSecurityClientAutoConfiguration}, run together with Spring Boot's OAuth2 client
 * auto-configuration so that the processing order is exercised.
 *
 * @author Martin Lindström
 */
class IamSecurityClientAutoConfigurationTest {

  private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          IamSecurityAutoConfiguration.class,
          IamSecurityClientAutoConfiguration.class,
          OAuth2ClientAutoConfiguration.class,
          OAuth2ClientWebSecurityAutoConfiguration.class))
      .withBean(JWK.class, () -> mock(JWK.class))
      // Suppresses Boot's default filter chain, which needs a full web security setup
      .withBean(SecurityFilterChain.class, () -> mock(SecurityFilterChain.class));

  private final WebApplicationContextRunner withRegistrations = this.contextRunner
      .withPropertyValues(
          "spring.security.oauth2.client.registration.demo.client-id=demo",
          "spring.security.oauth2.client.registration.demo.authorization-grant-type=authorization_code",
          "spring.security.oauth2.client.registration.demo.redirect-uri=https://app.example.com/callback",
          "spring.security.oauth2.client.registration.demo.scope={org}:{function}:read",
          "spring.security.oauth2.client.registration.demo.provider=kc",
          "spring.security.oauth2.client.provider.kc.authorization-uri=https://idp.example.com/auth",
          "spring.security.oauth2.client.provider.kc.token-uri=https://idp.example.com/token");

  @Test
  void withClientRegistrations_usesOrgKeyedRepository() {
    this.withRegistrations.run(context -> {
      assertThat(context).hasSingleBean(OAuth2AuthorizedClientRepository.class);
      assertThat(context.getBean(OAuth2AuthorizedClientRepository.class))
          .isInstanceOf(OrgKeyedAuthorizedClientRepository.class);
    });
  }

  @Test
  void withClientRegistrations_managerUsesOrgKeyedRepository() {
    this.withRegistrations.run(context -> {
      assertThat(context).hasSingleBean(OAuth2AuthorizedClientManager.class);
      final OAuth2AuthorizedClientManager manager = context.getBean(OAuth2AuthorizedClientManager.class);
      assertThat(manager).isInstanceOf(DefaultOAuth2AuthorizedClientManager.class);
      assertThat(ReflectionTestUtils.getField(manager, "authorizedClientRepository"))
          .isSameAs(context.getBean(OAuth2AuthorizedClientRepository.class));
    });
  }

  @Test
  void applicationRepository_takesPrecedence() {
    this.withRegistrations
        .withBean("customRepository", OAuth2AuthorizedClientRepository.class,
            HttpSessionOAuth2AuthorizedClientRepository::new)
        .run(context -> {
          assertThat(context).hasSingleBean(OAuth2AuthorizedClientRepository.class);
          final OAuth2AuthorizedClientRepository repository =
              context.getBean(OAuth2AuthorizedClientRepository.class);
          assertThat(repository).isInstanceOf(HttpSessionOAuth2AuthorizedClientRepository.class);
          assertThat(ReflectionTestUtils.getField(
              context.getBean(OAuth2AuthorizedClientManager.class), "authorizedClientRepository"))
              .isSameAs(repository);
        });
  }

  @Test
  void applicationManager_takesPrecedence() {
    // Same shape as the IAM admin app's service-account manager
    this.withRegistrations
        .withUserConfiguration(ServiceAccountManagerConfiguration.class)
        .run(context -> {
          assertThat(context).hasSingleBean(OAuth2AuthorizedClientManager.class);
          assertThat(context.getBean(OAuth2AuthorizedClientManager.class))
              .isInstanceOf(AuthorizedClientServiceOAuth2AuthorizedClientManager.class);
          assertThat(context.getBean(OAuth2AuthorizedClientRepository.class))
              .isInstanceOf(OrgKeyedAuthorizedClientRepository.class);
        });
  }

  @Test
  void withoutClientRegistrations_startsWithoutRepositoryOrManager() {
    this.contextRunner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(OAuth2AuthorizedClientRepository.class);
      assertThat(context).doesNotHaveBean(OAuth2AuthorizedClientManager.class);
    });
  }

  @Test
  void withoutBootOAuth2ClientAutoConfiguration_usesOrgKeyedRepository() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            IamSecurityAutoConfiguration.class, IamSecurityClientAutoConfiguration.class))
        .withBean(JWK.class, () -> mock(JWK.class))
        .withClassLoader(new FilteredClassLoader(
            "org.springframework.boot.security.oauth2.client.autoconfigure"))
        .withBean(ClientRegistrationRepository.class,
            () -> new InMemoryClientRegistrationRepository(registration()))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(OAuth2AuthorizedClientRepository.class))
              .isInstanceOf(OrgKeyedAuthorizedClientRepository.class);
          assertThat(context).hasSingleBean(OAuth2AuthorizedClientManager.class);
        });
  }

  private static ClientRegistration registration() {
    return ClientRegistration.withRegistrationId("demo")
        .clientId("demo")
        .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("https://app.example.com/callback")
        .scope("{org}:{function}:read")
        .authorizationUri("https://idp.example.com/auth")
        .tokenUri("https://idp.example.com/token")
        .build();
  }

  /**
   * Application configuration defining a service-account manager.
   */
  @Configuration(proxyBeanMethods = false)
  static class ServiceAccountManagerConfiguration {

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
        final ClientRegistrationRepository clientRegistrationRepository,
        final OAuth2AuthorizedClientService authorizedClientService) {
      return new AuthorizedClientServiceOAuth2AuthorizedClientManager(
          clientRegistrationRepository, authorizedClientService);
    }
  }

}
