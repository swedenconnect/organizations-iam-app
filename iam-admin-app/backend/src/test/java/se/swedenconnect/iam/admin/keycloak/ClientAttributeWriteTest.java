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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests that saving a client never relies on leaving an attribute out of the update.
 *
 * <p>Keycloak keeps an attribute that an update leaves out, and deletes one whose new value is
 * blank. A flag that no longer applies must therefore be written as {@code "false"}, and a JWKS
 * value that is not in use must be written empty. The mock Keycloak in this test applies each update
 * with those rules, so that what the application reads back is what Keycloak would hold.</p>
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClientAttributeWriteTest {

  private static final String ADMIN_API_BASE = "http://localhost:8080/admin/realms/orgiam";

  private static final String CLIENT_ID = "https://client.example.se";

  private static final String CLIENT_UUID = "c0ffee00-0000-4000-8000-000000000002";

  private static final String LOOKUP_URI =
      ADMIN_API_BASE + "/clients?clientId=https://client.example.se&exact=true";

  private static final String LIST_URI = ADMIN_API_BASE + "/clients?first=0&max=500";

  private static final String MAPPERS_URI =
      ADMIN_API_BASE + "/clients/" + CLIENT_UUID + "/protocol-mappers/models";

  private static final String JWKS_URI = "https://client.example.se/jwks";

  private static final String JWKS_STRING = "{\"keys\":[]}";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

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
  // Switching the JWKS mode
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Switching from a JWKS URI to an inline JWK Set turns the URI mode off")
  void switchFromUriToString() {
    final Saved saved = this.update(oidcClient(JWKS_URI, null), true, false, null, JWKS_STRING, true);

    assertThat(attribute(saved.written(), "use.jwks.url")).isEqualTo("false");
    assertThat(attribute(saved.written(), "jwks.url")).isEmpty();
    assertThat(attribute(saved.written(), "use.jwks.string")).isEqualTo("true");
    assertThat(attribute(saved.written(), "jwks.string")).isEqualTo(JWKS_STRING);

    assertThat(saved.readBack().jwksUri()).isNull();
    assertThat(saved.readBack().jwksString()).isEqualTo(JWKS_STRING);
    assertThat(saved.stored()).doesNotContainKey("jwks.url");
  }

  @Test
  @DisplayName("Switching from an inline JWK Set to a JWKS URI turns the inline mode off")
  void switchFromStringToUri() {
    final Saved saved = this.update(oidcClient(null, JWKS_STRING), true, false, JWKS_URI, null, true);

    assertThat(attribute(saved.written(), "use.jwks.url")).isEqualTo("true");
    assertThat(attribute(saved.written(), "jwks.url")).isEqualTo(JWKS_URI);
    assertThat(attribute(saved.written(), "use.jwks.string")).isEqualTo("false");
    assertThat(attribute(saved.written(), "jwks.string")).isEmpty();

    assertThat(saved.readBack().jwksUri()).isEqualTo(JWKS_URI);
    assertThat(saved.readBack().jwksString()).isNull();
    assertThat(saved.stored()).doesNotContainKey("jwks.string");
  }

  @Test
  @DisplayName("Saving a client in the same JWKS mode keeps the value it gives")
  void sameModeKeepsTheValue() {
    final Saved saved = this.update(oidcClient(JWKS_URI, null), true, false,
        "https://client.example.se/other-jwks", null, true);

    assertThat(saved.readBack().jwksUri()).isEqualTo("https://client.example.se/other-jwks");
    assertThat(saved.readBack().jwksString()).isNull();
  }

  // ---------------------------------------------------------------------------
  // Turning the roles off
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Turning off the OIDC client role clears the JWKS and the service account")
  void oidcClientRoleOff() {
    final Saved saved = this.update(oidcClient(JWKS_URI, null), false, true, null, null, false);

    assertThat(attribute(saved.written(), "use.jwks.url")).isEqualTo("false");
    assertThat(attribute(saved.written(), "use.jwks.string")).isEqualTo("false");
    assertThat(attribute(saved.written(), "jwks.url")).isEmpty();
    assertThat(attribute(saved.written(), "jwks.string")).isEmpty();
    assertThat(attribute(saved.written(), "iam_admin_service_account")).isEqualTo("false");
    assertThat(attribute(saved.written(), "iam_admin_oidc_client")).isEqualTo("false");

    assertThat(saved.stored()).doesNotContainKeys("jwks.url", "jwks.string");
    assertThat(saved.readBack().oidcClient()).isFalse();
    assertThat(saved.readBack().jwksUri()).isNull();
    assertThat(saved.readBack().jwksString()).isNull();
    assertThat(saved.readBack().serviceAccount()).isFalse();
  }

  @Test
  @DisplayName("Turning off the resource server role takes effect")
  void resourceServerRoleOff() {
    final ObjectNode current = oidcClient(JWKS_URI, null);
    ((ObjectNode) current.path("attributes")).put("iam_admin_resource_server", "true");

    final Saved saved = this.update(current, true, false, JWKS_URI, null, true);

    assertThat(attribute(saved.written(), "iam_admin_resource_server")).isEqualTo("false");
    assertThat(saved.readBack().resourceServer()).isFalse();
    assertThat(this.newClient().resolveResourceServers()).isEmpty();
  }

  @Test
  @DisplayName("Turning on the resource server role writes the marker as true")
  void resourceServerRoleOn() {
    final Saved saved = this.update(oidcClient(JWKS_URI, null), true, true, JWKS_URI, null, true);

    assertThat(attribute(saved.written(), "iam_admin_resource_server")).isEqualTo("true");
    assertThat(saved.readBack().resourceServer()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // Both JWKS mode flags
  // ---------------------------------------------------------------------------

  @ParameterizedTest(name = "oidcClient={0}, resourceServer={1}, jwks={2}")
  @CsvSource({
      "true,  false, uri",
      "true,  false, string",
      "true,  true,  uri",
      "true,  true,  string",
      "false, true,  none",
  })
  @DisplayName("A client with both JWKS mode flags stuck on never keeps both")
  void neverBothJwksFlags(final boolean oidcClient, final boolean resourceServer, final String jwks) {
    final ObjectNode current = oidcClient(JWKS_URI, JWKS_STRING);

    final Saved saved = this.update(current, oidcClient, resourceServer,
        "uri".equals(jwks) ? JWKS_URI : null, "string".equals(jwks) ? JWKS_STRING : null, oidcClient);

    assertThat("true".equals(attribute(saved.written(), "use.jwks.url"))
        && "true".equals(attribute(saved.written(), "use.jwks.string"))).isFalse();
    assertThat(saved.readBack().jwksUri() != null && saved.readBack().jwksString() != null).isFalse();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * The outcome of an update.
   *
   * @param written the attributes the application sent to Keycloak
   * @param stored the attributes Keycloak holds after applying the update
   * @param readBack the client as the application reads it back
   */
  private record Saved(JsonNode written, Map<String, String> stored, ManagedClientInfo readBack) {
  }

  /**
   * Updates the client and returns what was written, what Keycloak stores, and what is read back.
   *
   * @param current the client as Keycloak holds it before the update
   * @param oidcClient the OIDC client role
   * @param resourceServer the resource server role
   * @param jwksUri the JWKS URI, or {@code null}
   * @param jwksString the inline JWK Set, or {@code null}
   * @param serviceAccount whether a service account is wanted
   * @return the outcome
   */
  private Saved update(final ObjectNode current, final boolean oidcClient, final boolean resourceServer,
      final String jwksUri, final String jwksString, final boolean serviceAccount) {

    this.server.expect(requestTo(LOOKUP_URI))
        .andRespond(withSuccess("[" + current + "]", MediaType.APPLICATION_JSON));

    final AtomicReference<ObjectNode> stored = new AtomicReference<>(current);
    final AtomicReference<JsonNode> written = new AtomicReference<>();
    this.server.expect(requestTo(ADMIN_API_BASE + "/clients/" + CLIENT_UUID))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(request -> {
          final JsonNode body = read(((MockClientHttpRequest) request).getBodyAsString());
          written.set(body.path("attributes"));
          stored.set(apply(current, body));
        })
        .andRespond(withSuccess());

    if (oidcClient && resolveOidcClientRole(current)) {
      this.server.expect(requestTo(MAPPERS_URI)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    }

    this.server.expect(ExpectedCount.manyTimes(), requestTo(LIST_URI))
        .andRespond(request -> withSuccess("[" + stored.get() + "]", MediaType.APPLICATION_JSON)
            .createResponse(request));

    final ManagedClientInfo readBack = this.newClient().updateManagedClient(CLIENT_ID, null,
        oidcClient, resourceServer, List.of(), Set.of("demo"), jwksUri, jwksString, serviceAccount,
        true, true);

    return new Saved(written.get(), attributes(stored.get()), readBack);
  }

  /**
   * Applies an update the way Keycloak does: attributes left out are kept, attributes with a blank
   * value are deleted, all others are replaced.
   */
  private static ObjectNode apply(final ObjectNode current, final JsonNode update) {
    final ObjectNode result = (ObjectNode) update.deepCopy();
    final ObjectNode attributes = ((ObjectNode) current.path("attributes")).deepCopy();
    update.path("attributes").properties().forEach(e -> {
      if (e.getValue().isNull() || e.getValue().asString().isBlank()) {
        attributes.remove(e.getKey());
      }
      else {
        attributes.set(e.getKey(), e.getValue());
      }
    });
    result.set("attributes", attributes);
    return result;
  }

  private static boolean resolveOidcClientRole(final ObjectNode client) {
    return "true".equals(client.path("attributes").path("iam_admin_oidc_client").asString(null));
  }

  private static Map<String, String> attributes(final JsonNode client) {
    final Map<String, String> result = new LinkedHashMap<>();
    client.path("attributes").properties().forEach(e -> result.put(e.getKey(), e.getValue().asString()));
    return result;
  }

  private static String attribute(final JsonNode attributes, final String name) {
    return Optional.ofNullable(attributes.get(name)).map(JsonNode::asString).orElse(null);
  }

  /**
   * An OIDC client as Keycloak holds it, with the JWKS mode flags set for each value given.
   *
   * @param jwksUri the JWKS URI, or {@code null}
   * @param jwksString the inline JWK Set, or {@code null}
   * @return the representation
   */
  private static ObjectNode oidcClient(final String jwksUri, final String jwksString) {
    final ObjectNode client = MAPPER.createObjectNode();
    client.put("id", CLIENT_UUID);
    client.put("clientId", CLIENT_ID);
    client.put("publicClient", false);
    client.put("standardFlowEnabled", true);
    client.put("serviceAccountsEnabled", true);
    final ObjectNode attributes = client.putObject("attributes");
    attributes.put("iam_admin_managed", "true");
    attributes.put("iam_admin_oidc_client", "true");
    attributes.put("iam_admin_service_account", "true");
    attributes.put("client_functions", "demo");
    if (jwksUri != null) {
      attributes.put("use.jwks.url", "true");
      attributes.put("jwks.url", jwksUri);
    }
    if (jwksString != null) {
      attributes.put("use.jwks.string", "true");
      attributes.put("jwks.string", jwksString);
    }
    return client;
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
