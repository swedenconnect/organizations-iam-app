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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import se.swedenconnect.iam.security.claims.FunctionScopedAuthority;
import se.swedenconnect.iam.security.claims.OrgRightsClaimParser;
import se.swedenconnect.iam.security.claims.OrganizationalAuthority;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrgRightsOidcUserService}.
 *
 * @author Martin Lindström
 */
class OrgRightsOidcUserServiceTest {

  private final OrgRightsClaimParser parser = new OrgRightsClaimParser();

  private static OidcIdToken idTokenWithClaim(final Object orgRightsClaim) {
    final Map<String, Object> claims = new java.util.HashMap<>(Map.of(
        "sub", "test-user",
        "iss", "https://keycloak/realms/orgiam",
        "aud", List.of("my-client")
    ));
    if (orgRightsClaim != null) {
      claims.put("org_rights", orgRightsClaim);
    }
    return new OidcIdToken("token-value", Instant.now(), Instant.now().plusSeconds(300), claims);
  }

  private static OidcUserRequest buildUserRequest(final OidcIdToken idToken) {
    final ClientRegistration clientReg = ClientRegistration.withRegistrationId("test")
        .clientId("my-client")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("https://my-app/callback")
        .authorizationUri("https://keycloak/auth")
        .tokenUri("https://keycloak/token")
        .build();

    final OAuth2AccessToken accessToken = new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER, "access-token-value",
        Instant.now(), Instant.now().plusSeconds(300));

    return new OidcUserRequest(clientReg, accessToken, idToken);
  }

  @Test
  void fullMode_producesOrganizationalAuthorities() {
    final OidcIdToken idToken = idTokenWithClaim(List.of(Map.of(
        "organization_identifier", "5590026042",
        "organization_name#sv", "Test Org",
        "functions", List.of(Map.of("function", "demo", "right", "write"))
    )));
    final OidcUser fakeUser = new DefaultOidcUser(List.of(), idToken);
    final OidcUserRequest userRequest = buildUserRequest(idToken);

    try (var mocked = mockConstruction(OidcUserService.class, (mock, context) ->
        when(mock.loadUser(any())).thenReturn(fakeUser))) {

      final OrgRightsOidcUserService service = new OrgRightsOidcUserService(this.parser);
      final OidcUser result = service.loadUser(userRequest);

      final Collection<? extends GrantedAuthority> authorities = result.getAuthorities();
      assertThat(authorities).hasSize(1);
      assertThat(authorities.iterator().next()).isInstanceOf(OrganizationalAuthority.class);
      assertThat(authorities.iterator().next().getAuthority()).isEqualTo("5590026042:demo:write");
    }
  }

  @Test
  void functionScopedMode_producesFunctionScopedAuthorities() {
    final OidcIdToken idToken = idTokenWithClaim(List.of(Map.of(
        "organization_identifier", "5590026042",
        "organization_name#sv", "Test Org",
        "functions", List.of(Map.of("function", "demo", "right", "write"))
    )));
    final OidcUser fakeUser = new DefaultOidcUser(List.of(), idToken);
    final OidcUserRequest userRequest = buildUserRequest(idToken);

    try (var mocked = mockConstruction(OidcUserService.class, (mock, context) ->
        when(mock.loadUser(any())).thenReturn(fakeUser))) {

      final OrgRightsOidcUserService service = new OrgRightsOidcUserService(this.parser, "demo");
      final OidcUser result = service.loadUser(userRequest);

      final Collection<? extends GrantedAuthority> authorities = result.getAuthorities();
      assertThat(authorities).hasSize(1);
      assertThat(authorities.iterator().next()).isInstanceOf(FunctionScopedAuthority.class);
      assertThat(authorities.iterator().next().getAuthority()).isEqualTo("5590026042:write");
    }
  }

  @Test
  void superuser_receivesRoleSuperuser() {
    final OidcIdToken idToken = idTokenWithClaim(List.of(Map.of("superuser", true)));
    final OidcUser fakeUser = new DefaultOidcUser(List.of(), idToken);
    final OidcUserRequest userRequest = buildUserRequest(idToken);

    try (var mocked = mockConstruction(OidcUserService.class, (mock, context) ->
        when(mock.loadUser(any())).thenReturn(fakeUser))) {

      final OrgRightsOidcUserService service = new OrgRightsOidcUserService(this.parser);
      final OidcUser result = service.loadUser(userRequest);

      assertThat(result.getAuthorities())
          .extracting(GrantedAuthority::getAuthority)
          .containsExactly("ROLE_SUPERUSER");
    }
  }

  @Test
  void noOrgRightsClaim_producesEmptyAuthorities() {
    final OidcIdToken idToken = idTokenWithClaim(null);
    final OidcUser fakeUser = new DefaultOidcUser(List.of(), idToken);
    final OidcUserRequest userRequest = buildUserRequest(idToken);

    try (var mocked = mockConstruction(OidcUserService.class, (mock, context) ->
        when(mock.loadUser(any())).thenReturn(fakeUser))) {

      final OrgRightsOidcUserService service = new OrgRightsOidcUserService(this.parser);
      final OidcUser result = service.loadUser(userRequest);

      assertThat(result.getAuthorities()).isEmpty();
    }
  }

}
