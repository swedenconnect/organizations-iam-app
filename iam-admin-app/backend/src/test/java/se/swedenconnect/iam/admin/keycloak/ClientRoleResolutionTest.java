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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests how the two client roles are read off a KeyCloak client representation.
 *
 * <p>In KeyCloak everything registered is a "client", whichever role it plays, so
 * {@code iam_admin_managed} says the application administers the client and nothing more.
 * {@code iam_admin_oidc_client} and {@code iam_admin_resource_server} carry the roles.</p>
 *
 * <p>{@code iam_admin_managed} used to carry the OIDC client role itself, so realms hold clients
 * written under both meanings. The legacy cases below are the ones that decide whether an existing
 * realm survives the change: a client read as not-OIDC loses every scope, policy and permission it
 * holds on the next reconciliation.</p>
 *
 * @author Felix Hellman
 */
class ClientRoleResolutionTest {

  @Nested
  @DisplayName("Clients written before iam_admin_oidc_client existed")
  class Legacy {

    @Test
    @DisplayName("An OIDC client keeps the OIDC client role")
    void oidcClient() {
      final Map<String, Object> client = client(Map.of("iam_admin_managed", "true"));

      assertThat(KeycloakAdminClient.resolveOidcClientRole(client)).isTrue();
      assertThat(KeycloakAdminClient.resolveResourceServerRole(client)).isFalse();
      assertThat(KeycloakAdminClient.administered(client)).isTrue();
    }

    @Test
    @DisplayName("A dual-role client keeps both roles")
    void dualRoleClient() {
      // The case a naive fallback gets wrong. Reading the resource server marker as evidence
      // against the OIDC client role would strip this client of every artifact it holds.
      final Map<String, Object> client = client(Map.of(
          "iam_admin_managed", "true",
          "iam_admin_resource_server", "true"));

      assertThat(KeycloakAdminClient.resolveOidcClientRole(client)).isTrue();
      assertThat(KeycloakAdminClient.resolveResourceServerRole(client)).isTrue();
      assertThat(KeycloakAdminClient.administered(client)).isTrue();
    }

    @Test
    @DisplayName("A resource server is administered without holding the OIDC client role")
    void resourceServerOnly() {
      // Registered by add-resource-server.sh, which never wrote iam_admin_managed.
      final Map<String, Object> client = client(Map.of("iam_admin_resource_server", "true"));

      assertThat(KeycloakAdminClient.resolveOidcClientRole(client)).isFalse();
      assertThat(KeycloakAdminClient.resolveResourceServerRole(client)).isTrue();
      assertThat(KeycloakAdminClient.administered(client)).isTrue();
    }
  }

  @Nested
  @DisplayName("Clients written with the role attributes")
  class Current {

    @Test
    @DisplayName("An OIDC client holds the OIDC client role")
    void oidcClient() {
      final Map<String, Object> client = client(Map.of(
          "iam_admin_managed", "true",
          "iam_admin_oidc_client", "true"));

      assertThat(KeycloakAdminClient.resolveOidcClientRole(client)).isTrue();
      assertThat(KeycloakAdminClient.resolveResourceServerRole(client)).isFalse();
      assertThat(KeycloakAdminClient.administered(client)).isTrue();
    }

    @Test
    @DisplayName("A resource server is administered but holds no OIDC client role")
    void resourceServerOnly() {
      // The explicit "false" is what stops the legacy fallback from reading iam_admin_managed
      // as the role and handing this client artifacts it must not have.
      final Map<String, Object> client = client(Map.of(
          "iam_admin_managed", "true",
          "iam_admin_oidc_client", "false",
          "iam_admin_resource_server", "true"));

      assertThat(KeycloakAdminClient.resolveOidcClientRole(client)).isFalse();
      assertThat(KeycloakAdminClient.resolveResourceServerRole(client)).isTrue();
      assertThat(KeycloakAdminClient.administered(client)).isTrue();
    }

    @Test
    @DisplayName("A dual-role client holds both roles")
    void dualRoleClient() {
      final Map<String, Object> client = client(Map.of(
          "iam_admin_managed", "true",
          "iam_admin_oidc_client", "true",
          "iam_admin_resource_server", "true"));

      assertThat(KeycloakAdminClient.resolveOidcClientRole(client)).isTrue();
      assertThat(KeycloakAdminClient.resolveResourceServerRole(client)).isTrue();
      assertThat(KeycloakAdminClient.administered(client)).isTrue();
    }
  }

  @Test
  @DisplayName("A client with no markers is not administered and holds no role")
  void strangerClient() {
    final Map<String, Object> client = client(Map.of());

    assertThat(KeycloakAdminClient.resolveOidcClientRole(client)).isFalse();
    assertThat(KeycloakAdminClient.resolveResourceServerRole(client)).isFalse();
    assertThat(KeycloakAdminClient.administered(client)).isFalse();
  }

  @Test
  @DisplayName("A client with no attributes map at all is handled")
  void clientWithoutAttributes() {
    final Map<String, Object> client = new LinkedHashMap<>();
    client.put("id", "uuid-1");
    client.put("clientId", "https://stranger.example.se");

    assertThat(KeycloakAdminClient.resolveOidcClientRole(client)).isFalse();
    assertThat(KeycloakAdminClient.administered(client)).isFalse();
  }

  @Test
  @DisplayName("A blank iam_admin_oidc_client falls back to the legacy meaning")
  void blankRoleMarkerFallsBack() {
    // KeyCloak returns "" for an attribute cleared in the admin console rather than dropping it.
    final Map<String, Object> client = client(Map.of(
        "iam_admin_managed", "true",
        "iam_admin_oidc_client", ""));

    assertThat(KeycloakAdminClient.resolveOidcClientRole(client)).isTrue();
  }

  private static Map<String, Object> client(final Map<String, String> attributes) {
    final Map<String, Object> client = new LinkedHashMap<>();
    client.put("id", "uuid-1");
    client.put("clientId", "https://app.example.se");
    client.put("attributes", new LinkedHashMap<String, Object>(attributes));
    return client;
  }
}
