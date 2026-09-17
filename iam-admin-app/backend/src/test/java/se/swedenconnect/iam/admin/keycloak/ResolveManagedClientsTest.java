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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.keycloak.model.ManagedClientInfo;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests for {@link KeycloakAdminClient#resolveIamAdminManagedClients()} and
 * {@link KeycloakAdminClient#resolveResourceServers()}.
 *
 * <p>A client is managed when, and only when, it carries {@code iam_admin_managed=true}. There is
 * no configuration by which a client can be made managed.</p>
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResolveManagedClientsTest {

  private static final String ADMIN_API_BASE = "http://localhost:8080/admin/realms/orgiam";

  private static final String CLIENTS_URI = ADMIN_API_BASE + "/clients?first=0&max=500";

  @Mock
  private OAuth2AuthorizedClientManager authorizedClientManager;

  private RestClient.Builder restClientBuilder;

  private MockRestServiceServer server;

  @BeforeEach
  void setUp() {
    final OAuth2AuthorizedClient authorizedClient = mock(OAuth2AuthorizedClient.class);
    final OAuth2AccessToken accessToken = new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER, "fake-token", Instant.now(), Instant.now().plusSeconds(300));
    lenient().when(authorizedClient.getAccessToken()).thenReturn(accessToken);
    lenient().when(this.authorizedClientManager.authorize(any())).thenReturn(authorizedClient);

    this.restClientBuilder = RestClient.builder();
    this.server = MockRestServiceServer.bindTo(this.restClientBuilder).build();
  }

  /** Only the clients carrying the attribute are managed, and they keep their functions. */
  @Test
  void managedAttribute_isWhatMakesAClientManaged() {
    final List<ManagedClientInfo> result = this.resolve("""
        [
          %s,
          %s
        ]
        """.formatted(
        client("uuid-1", "client-one", true, false, "demo,other"),
        client("uuid-2", "client-two", false, false, "demo")));

    assertThat(result).singleElement().satisfies(info -> {
      assertThat(info.clientId()).isEqualTo("client-one");
      assertThat(info.uuid()).isEqualTo("uuid-1");
      assertThat(info.oidcClient()).isTrue();
      assertThat(info.handles("demo")).isTrue();
      assertThat(info.handles("third")).isFalse();
    });
  }

  /** A client with no attributes at all is not managed. */
  @Test
  void clientWithoutAttributes_isNotManaged() {
    final List<ManagedClientInfo> result = this.resolve("""
        [
          {"id": "uuid-3", "clientId": "plain-client"}
        ]
        """);

    assertThat(result).isEmpty();
  }

  /** A realm holding no managed client at all resolves to an empty list rather than failing. */
  @Test
  void noManagedClients_returnsEmptyList() {
    assertThat(this.resolve("[]")).isEmpty();
  }

  /**
   * A client that is only a resource server is not an OIDC client, so it is not returned by the
   * managed-client resolution even though the application administers it.
   */
  @Test
  void resourceServerOnly_isNotAManagedClient() {
    final String body = "[%s]".formatted(client("uuid-4", "resource-server", false, true, null));
    this.expectClients(body, 2);
    final KeycloakAdminClient client = this.newClient();

    assertThat(client.resolveIamAdminManagedClients()).isEmpty();
    assertThat(client.resolveResourceServers()).singleElement()
        .satisfies(info -> assertThat(info.clientId()).isEqualTo("resource-server"));
  }

  /** Both roles may be set on one client; it is then managed and a resource server. */
  @Test
  void bothRoles_onOneClient() {
    final String body = "[%s]".formatted(client("uuid-5", "both-roles", true, true, "demo"));

    assertThat(this.resolve(body)).singleElement().satisfies(info -> {
      assertThat(info.oidcClient()).isTrue();
      assertThat(info.resourceServer()).isTrue();
    });
  }

  /** A representation without an id or a clientId cannot be turned into a managed client. */
  @Test
  void representationWithoutId_isSkipped() {
    final List<ManagedClientInfo> result = this.resolve("""
        [
          {"clientId": "no-uuid", "attributes": {"iam_admin_managed": "true"}},
          {"id": "uuid-6", "attributes": {"iam_admin_managed": "true"}}
        ]
        """);

    assertThat(result).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private List<ManagedClientInfo> resolve(final String clientsJson) {
    this.expectClients(clientsJson, 1);
    return this.newClient().resolveIamAdminManagedClients();
  }

  private void expectClients(final String clientsJson, final int times) {
    this.server.expect(ExpectedCount.times(times), requestTo(CLIENTS_URI))
        .andRespond(withSuccess(clientsJson, MediaType.APPLICATION_JSON));
  }

  private KeycloakAdminClient newClient() {
    final IamAdminProperties props = new IamAdminProperties();
    props.setAdminApiBase(ADMIN_API_BASE);

    return new KeycloakAdminClient(this.authorizedClientManager, this.restClientBuilder, props);
  }

  /**
   * A Keycloak client representation. {@code iam_admin_service_account} is always set, so that
   * reading the client never needs a further Keycloak call.
   */
  private static String client(
      final String uuid,
      final String clientId,
      final boolean managed,
      final boolean resourceServer,
      final String functions) {

    final String functionsAttribute = functions == null ? "" : ", \"client_functions\": \"%s\"".formatted(functions);
    return """
        {
          "id": "%s",
          "clientId": "%s",
          "attributes": {
            "iam_admin_managed": "%s",
            "iam_admin_resource_server": "%s",
            "iam_admin_service_account": "false"%s
          }
        }
        """.formatted(uuid, clientId, managed, resourceServer, functionsAttribute);
  }
}
