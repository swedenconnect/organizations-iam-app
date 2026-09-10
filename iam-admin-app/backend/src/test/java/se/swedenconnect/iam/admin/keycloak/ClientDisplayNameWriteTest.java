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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.keycloak.model.ManagedClientInfo;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests that the display name reaches Keycloak whichever roles a client holds.
 *
 * <p>The name is not an OIDC client setting. A resource server carries one too, and it is what
 * identifies the client in the Keycloak admin console and in the client list, so it must be written
 * on create and on update for a client that is only a resource server.</p>
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClientDisplayNameWriteTest {

  private static final String ADMIN_API_BASE = "http://localhost:8080/admin/realms/orgiam";

  private static final String CLIENT_ID = "https://registry.example.se";

  private static final String CLIENT_UUID = "c0ffee00-0000-4000-8000-000000000001";

  private static final String LOOKUP_URI =
      ADMIN_API_BASE + "/clients?clientId=https://registry.example.se&exact=true";

  private static final String LIST_URI = ADMIN_API_BASE + "/clients?first=0&max=500";

  private static final ObjectMapper MAPPER = new ObjectMapper();

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

  // ---------------------------------------------------------------------------
  // Create
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("A resource-server-only client is created with its display name")
  void create_resourceServerOnly_writesTheName() {
    final JsonNode body = this.captureCreate("Registry");

    assertThat(body.path("name").asText()).isEqualTo("Registry");
    assertThat(body.path("publicClient").asBoolean()).isTrue();
    assertThat(body.path("standardFlowEnabled").asBoolean()).isFalse();
    assertThat(body.path("attributes").path("iam_admin_resource_server").asText()).isEqualTo("true");
    assertThat(body.path("attributes").path("iam_admin_oidc_client").asText()).isEqualTo("false");
  }

  @Test
  @DisplayName("A resource server may be created with no display name at all")
  void create_resourceServerOnly_withoutAName() {
    final JsonNode body = this.captureCreate(null);

    assertThat(body.has("name")).isFalse();
    assertThat(body.path("attributes").path("iam_admin_resource_server").asText()).isEqualTo("true");
  }

  @Test
  @DisplayName("An empty display name is written as it is, rather than being dropped")
  void create_resourceServerOnly_withAnEmptyName() {
    assertThat(this.captureCreate("").path("name").asText()).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Update
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Updating a resource server writes back the display name the form holds")
  void update_resourceServerOnly_writesTheName() {
    final JsonNode body = this.captureUpdate("Registry", "Client Registry");

    assertThat(body.path("name").asText()).isEqualTo("Client Registry");
  }

  @Test
  @DisplayName("A resource server registered by script keeps its name when the update omits one")
  void update_resourceServerOnly_omittedNameKeepsTheExistingOne() {
    final JsonNode body = this.captureUpdate("Registry", null);

    assertThat(body.path("name").asText()).isEqualTo("Registry");
  }

  @Test
  @DisplayName("A name is given to a resource server that had none")
  void update_resourceServerOnly_addsANameToAClientWithout() {
    final JsonNode body = this.captureUpdate(null, "Client Registry");

    assertThat(body.path("name").asText()).isEqualTo("Client Registry");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Creates a resource-server-only client with the given name and returns the body of the PUT that
   * writes the client's settings.
   *
   * @param name the display name to create the client with, or {@code null} for none
   * @return the captured request body
   */
  private JsonNode captureCreate(final String name) {
    this.server.expect(requestTo(ADMIN_API_BASE + "/clients"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withCreatedEntity(URI.create(ADMIN_API_BASE + "/clients/" + CLIENT_UUID)));
    this.server.expect(requestTo(LOOKUP_URI))
        .andRespond(withSuccess("[" + representation(null) + "]", MediaType.APPLICATION_JSON));

    final AtomicReference<String> captured = this.expectSettingsPut();

    // resolveAdministeredClients lists the clients once for each role it resolves
    this.server.expect(ExpectedCount.times(2), requestTo(LIST_URI))
        .andRespond(withSuccess("[" + representation(name) + "]", MediaType.APPLICATION_JSON));

    final ManagedClientInfo created = this.newClient().createManagedClient(
        CLIENT_ID, name, false, true, List.of(), Set.of("demo"), null, null, false, true, true);

    this.server.verify();
    assertThat(created.resourceServer()).isTrue();
    return read(captured.get());
  }

  /**
   * Updates a resource-server-only client and returns the body of the PUT that writes its settings.
   *
   * @param existingName the display name the client carries in Keycloak, or {@code null} for none
   * @param newName the display name the update supplies, or {@code null} to supply none
   * @return the captured request body
   */
  private JsonNode captureUpdate(final String existingName, final String newName) {
    this.server.expect(requestTo(LOOKUP_URI))
        .andRespond(withSuccess("[" + representation(existingName) + "]", MediaType.APPLICATION_JSON));

    final AtomicReference<String> captured = this.expectSettingsPut();

    this.server.expect(ExpectedCount.times(2), requestTo(LIST_URI))
        .andRespond(withSuccess(
            "[" + representation(newName == null ? existingName : newName) + "]",
            MediaType.APPLICATION_JSON));

    this.newClient().updateManagedClient(
        CLIENT_ID, newName, false, true, List.of(), Set.of("demo"), null, null, false, true, true);

    this.server.verify();
    return read(captured.get());
  }

  /** Captures the body of the PUT that writes the client's settings. */
  private AtomicReference<String> expectSettingsPut() {
    final AtomicReference<String> captured = new AtomicReference<>();
    this.server.expect(requestTo(ADMIN_API_BASE + "/clients/" + CLIENT_UUID))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(request -> captured.set(((MockClientHttpRequest) request).getBodyAsString()))
        .andRespond(withSuccess());
    return captured;
  }

  /**
   * A Keycloak representation of the resource server, with or without a display name.
   *
   * @param name the display name, or {@code null} for a representation carrying none
   * @return the JSON representation
   */
  private static String representation(final String name) {
    return """
        {
          "id": "%s",
          "clientId": "%s",
          %s
          "publicClient": true,
          "standardFlowEnabled": false,
          "serviceAccountsEnabled": false,
          "attributes": {
            "iam_admin_managed": "true",
            "iam_admin_resource_server": "true",
            "iam_admin_oidc_client": "false",
            "client_functions": "demo"
          }
        }
        """.formatted(CLIENT_UUID, CLIENT_ID, name == null ? "" : "\"name\": \"" + name + "\",");
  }

  private static JsonNode read(final String body) {
    try {
      return MAPPER.readTree(body);
    }
    catch (final Exception e) {
      throw new IllegalStateException("Could not read the captured request body", e);
    }
  }

  private KeycloakAdminClient newClient() {
    final IamAdminProperties props = new IamAdminProperties();
    props.setAdminApiBase(ADMIN_API_BASE);
    return new KeycloakAdminClient(this.authorizedClientManager, this.restClientBuilder, props);
  }

}
