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
package se.swedenconnect.iam.admin.keycloak;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.keycloak.model.ClientArtifactState;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests how the base optional client scopes are counted and added on a client:
 * {@link KeycloakAdminClient#countMissingBaseOptionalScopes} and
 * {@link KeycloakAdminClient#ensureBaseOptionalScopes}. The two have to agree, so that a client
 * counted as missing nothing is one a reconciliation leaves untouched.
 *
 * @author Martin Lindström
 */
class BaseOptionalScopesTest {

  private static final String ADMIN_API_BASE = "http://localhost:8080/admin/realms/orgiam";

  private static final String CLIENT_UUID = "client-uuid";

  /** A realm holding every base optional scope. */
  private static final Map<String, String> REALM = Map.of(
      "https://id.oidc.se/scope/naturalPersonNumber", "id-npn",
      "https://id.oidc.se/scope/naturalPersonOrgId", "id-orgid",
      "https://id.swedenconnect.se/scope/primary-identifier", "id-primary",
      "phone", "id-phone");

  private RestClient.Builder restClientBuilder;

  private MockRestServiceServer server;

  @BeforeEach
  void setUp() {
    this.restClientBuilder = RestClient.builder();
    this.server = MockRestServiceServer.bindTo(this.restClientBuilder).build();
  }

  /** A client holding nothing lacks every base scope. */
  @Test
  void emptyClient_missesEveryBaseScope() {
    assertThat(KeycloakAdminClient.countMissingBaseOptionalScopes(REALM, state(Set.of(), Set.of()))).isEqualTo(4);
  }

  /** A client holding every base scope lacks nothing, and a reconciliation makes no request. */
  @Test
  void completeClient_missesNothingAndIsLeftUntouched() {
    final ClientArtifactState state = state(Set.of("id-npn", "id-orgid", "id-primary", "id-phone"), Set.of());

    assertThat(KeycloakAdminClient.countMissingBaseOptionalScopes(REALM, state)).isZero();
    assertThat(this.newClient().ensureBaseOptionalScopes(CLIENT_UUID, REALM, state)).isZero();
    this.server.verify();
  }

  /** A scope the client holds as a default scope counts as held, and is not added as optional. */
  @Test
  void defaultScope_countsAsHeld() {
    final ClientArtifactState state = state(Set.of("id-npn", "id-orgid", "id-primary"), Set.of("id-phone"));

    assertThat(KeycloakAdminClient.countMissingBaseOptionalScopes(REALM, state)).isZero();
    assertThat(this.newClient().ensureBaseOptionalScopes(CLIENT_UUID, REALM, state)).isZero();
    this.server.verify();
  }

  /**
   * A client registered before the primary identifier scope existed lacks only that scope, and has
   * it added as an optional scope.
   */
  @Test
  void clientWithoutPrimaryIdentifierScope_getsIt() {
    final ClientArtifactState state = state(Set.of("id-npn", "id-orgid", "id-phone"), Set.of());
    this.server.expect(requestTo(ADMIN_API_BASE + "/clients/" + CLIENT_UUID + "/optional-client-scopes/id-primary"))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(withSuccess());

    assertThat(KeycloakAdminClient.countMissingBaseOptionalScopes(REALM, state)).isEqualTo(1);
    assertThat(this.newClient().ensureBaseOptionalScopes(CLIENT_UUID, REALM, state)).isEqualTo(1);
    this.server.verify();
  }

  /**
   * A scope the realm does not have is neither counted nor added, and does not stop the other
   * scopes from being added.
   */
  @Test
  void scopeMissingInRealm_isSkipped() {
    final Map<String, String> realm = Map.of(
        "https://id.oidc.se/scope/naturalPersonNumber", "id-npn",
        "phone", "id-phone");
    final ClientArtifactState state = state(Set.of(), Set.of());
    this.server.expect(requestTo(ADMIN_API_BASE + "/clients/" + CLIENT_UUID + "/optional-client-scopes/id-npn"))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(withSuccess());
    this.server.expect(requestTo(ADMIN_API_BASE + "/clients/" + CLIENT_UUID + "/optional-client-scopes/id-phone"))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(withSuccess());

    assertThat(KeycloakAdminClient.warnMissingBaseOptionalScopes(realm)).containsExactly(
        "https://id.oidc.se/scope/naturalPersonOrgId",
        "https://id.swedenconnect.se/scope/primary-identifier");
    assertThat(KeycloakAdminClient.countMissingBaseOptionalScopes(realm, state)).isEqualTo(2);
    assertThat(this.newClient().ensureBaseOptionalScopes(CLIENT_UUID, realm, state)).isEqualTo(2);
    this.server.verify();
  }

  private static ClientArtifactState state(final Set<String> optional, final Set<String> defaults) {
    return new ClientArtifactState(Set.of(), Set.of(), Set.of(), optional, defaults);
  }

  private KeycloakAdminClient newClient() {
    final OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
    final OAuth2AuthorizedClient authorizedClient = mock(OAuth2AuthorizedClient.class);
    when(authorizedClient.getAccessToken()).thenReturn(new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER, "fake-token", Instant.now(), Instant.now().plusSeconds(300)));
    when(manager.authorize(any())).thenReturn(authorizedClient);

    final IamAdminProperties props = new IamAdminProperties();
    props.setAdminApiBase(ADMIN_API_BASE);
    return new KeycloakAdminClient(manager, this.restClientBuilder, props);
  }

}
