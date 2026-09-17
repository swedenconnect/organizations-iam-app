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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.client.RestClient;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.keycloak.model.ManagedClientInfo;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Tests that {@link KeycloakAdminClient#findManagedClient(String)} covers both client roles.
 *
 * <p>Regression test for the case where registering a resource-server-only client through the
 * application failed with <em>"is not managed after creation"</em>. The client was written to
 * KeyCloak correctly, but the read-back at the end of
 * {@link KeycloakAdminClient#createManagedClient} resolved only the OIDC clients. The resource
 * server role does not set {@code iam_admin_managed}, so the newly created client was never
 * among them and the whole request failed after the client had already been created.</p>
 *
 * @author Felix Hellman
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FindAdministeredClientTest {

  private static final String OIDC_CLIENT_ID = "https://demo-app.example.se";
  private static final String RESOURCE_SERVER_ID = "https://local.dev.swedenconnect.se:20001";

  @Mock
  private OAuth2AuthorizedClientManager authorizedClientManager;

  private KeycloakAdminClient client;

  @BeforeEach
  void setUp() {
    final IamAdminProperties props = new IamAdminProperties();
    props.setAdminApiBase("http://localhost:8080/admin/realms/orgiam");

    final OAuth2AuthorizedClient authorizedClient = mock(OAuth2AuthorizedClient.class);
    final OAuth2AccessToken accessToken = new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER, "fake-token",
        Instant.now(), Instant.now().plusSeconds(300));
    lenient().when(authorizedClient.getAccessToken()).thenReturn(accessToken);
    lenient().when(this.authorizedClientManager.authorize(any())).thenReturn(authorizedClient);

    this.client = spy(new KeycloakAdminClient(
        this.authorizedClientManager, RestClient.builder(), props));

    doReturn(List.of(oidcClient())).when(this.client).resolveIamAdminManagedClients();
    doReturn(List.of(resourceServer())).when(this.client).resolveResourceServers();
  }

  @Test
  @DisplayName("A resource-server-only client is found by client_id")
  void resourceServerOnlyClientIsFound() {
    final ManagedClientInfo found = this.client.findManagedClient(RESOURCE_SERVER_ID).orElse(null);

    assertThat(found).isNotNull();
    assertThat(found.clientId()).isEqualTo(RESOURCE_SERVER_ID);
    assertThat(found.oidcClient()).isFalse();
    assertThat(found.resourceServer()).isTrue();
  }

  @Test
  @DisplayName("An OIDC client is still found by client_id")
  void oidcClientIsFound() {
    final ManagedClientInfo found = this.client.findManagedClient(OIDC_CLIENT_ID).orElse(null);

    assertThat(found).isNotNull();
    assertThat(found.clientId()).isEqualTo(OIDC_CLIENT_ID);
    assertThat(found.oidcClient()).isTrue();
  }

  @Test
  @DisplayName("A client the application does not administer is not found")
  void unadministeredClientIsNotFound() {
    assertThat(this.client.findManagedClient("https://stranger.example.se")).isEmpty();
  }

  @Test
  @DisplayName("A client holding both roles is listed once, not once per role")
  void dualRoleClientIsListedOnce() {
    // The IAM Admin application is the first client to hold both roles, so it appears in both
    // source lists. GET /api/clients renders this list, and a client listed twice reads as two
    // clients.
    final ManagedClientInfo dual = new ManagedClientInfo(
        "uuid-dual", "https://iam.example.se", "IAM Admin", true, true,
        Set.of("demo"), true, List.of(), null, null, true, true, true, true);
    doReturn(List.of(dual)).when(this.client).resolveIamAdminManagedClients();
    doReturn(List.of(dual)).when(this.client).resolveResourceServers();

    final List<ManagedClientInfo> administered = this.client.resolveAdministeredClients();

    assertThat(administered).hasSize(1);
    assertThat(administered.getFirst().clientId()).isEqualTo("https://iam.example.se");
    assertThat(administered.getFirst().oidcClient()).isTrue();
    assertThat(administered.getFirst().resourceServer()).isTrue();
  }

  @Test
  @DisplayName("An OIDC client and a separate resource server are both listed")
  void distinctClientsAreBothListed() {
    assertThat(this.client.resolveAdministeredClients())
        .extracting(ManagedClientInfo::clientId)
        .containsExactly(OIDC_CLIENT_ID, RESOURCE_SERVER_ID);
  }

  private static ManagedClientInfo oidcClient() {
    return new ManagedClientInfo(
        "uuid-oidc", OIDC_CLIENT_ID, "Demo Application", true, false,
        Set.of("demo"), false,
        List.of(OIDC_CLIENT_ID + "/login/oauth2/code/orgiam"),
        OIDC_CLIENT_ID + "/jwks", null, false, true, true, true);
  }

  private static ManagedClientInfo resourceServer() {
    return new ManagedClientInfo(
        "uuid-rs", RESOURCE_SERVER_ID, "My API", false, true,
        Set.of("demo"), false,
        List.of(), null, null, false, true, true, true);
  }
}
