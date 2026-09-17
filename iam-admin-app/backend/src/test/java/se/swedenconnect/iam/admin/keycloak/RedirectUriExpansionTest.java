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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keycloak permits a redirect URI given as a path and resolves it against the client root URL.
 * {@link KeycloakAdminClient#expandRedirectUri(String, String)} joins the two so the user sees where
 * the callback actually goes.
 *
 * @author Martin Lindström
 */
class RedirectUriExpansionTest {

  private static final String ROOT = "https://demo-app.example.se";

  @Test
  @DisplayName("A relative URI is joined to the root URL with exactly one separator")
  void relativeUriIsJoinedToRootUrl() {
    assertThat(KeycloakAdminClient.expandRedirectUri("/login/oauth2/code/orgiam", ROOT))
        .isEqualTo("https://demo-app.example.se/login/oauth2/code/orgiam");
  }

  @Test
  @DisplayName("A path without a leading slash gets one")
  void relativeUriWithoutLeadingSlash() {
    assertThat(KeycloakAdminClient.expandRedirectUri("login/oauth2/code/orgiam", ROOT))
        .isEqualTo("https://demo-app.example.se/login/oauth2/code/orgiam");
  }

  @Test
  @DisplayName("A root URL with a trailing slash does not produce a doubled separator")
  void rootUrlWithTrailingSlash() {
    assertThat(KeycloakAdminClient.expandRedirectUri("/cb", ROOT + "/"))
        .isEqualTo("https://demo-app.example.se/cb");
    assertThat(KeycloakAdminClient.expandRedirectUri("cb", ROOT + "/"))
        .isEqualTo("https://demo-app.example.se/cb");
  }

  @Test
  @DisplayName("A trailing wildcard is carried along untouched")
  void relativeUriWithTrailingWildcard() {
    assertThat(KeycloakAdminClient.expandRedirectUri("/login/oauth2/code/*", ROOT))
        .isEqualTo("https://demo-app.example.se/login/oauth2/code/*");
  }

  @Test
  @DisplayName("A root URL carrying a path is preserved")
  void rootUrlWithPath() {
    assertThat(KeycloakAdminClient.expandRedirectUri("/cb", ROOT + "/app"))
        .isEqualTo("https://demo-app.example.se/app/cb");
  }

  @Test
  @DisplayName("An absolute URI is returned unchanged, root URL or not")
  void absoluteUriIsUnchanged() {
    final String absolute = "https://other.example.se/cb";
    assertThat(KeycloakAdminClient.expandRedirectUri(absolute, ROOT)).isEqualTo(absolute);
    assertThat(KeycloakAdminClient.expandRedirectUri(absolute, null)).isEqualTo(absolute);
    assertThat(KeycloakAdminClient.expandRedirectUri("https://other.example.se/cb/*", ROOT))
        .isEqualTo("https://other.example.se/cb/*");
  }

  /**
   * Without a root URL the path stands as it is. Keycloak would resolve it against the auth server
   * root URL, but that is not known here and guessing would display a callback that is not the real
   * one. Such a URI is rejected on save instead.
   */
  @Test
  @DisplayName("A relative URI is left alone when the client has no root URL")
  void relativeUriWithoutRootUrlIsUnchanged() {
    assertThat(KeycloakAdminClient.expandRedirectUri("/cb", null)).isEqualTo("/cb");
    assertThat(KeycloakAdminClient.expandRedirectUri("/cb", "")).isEqualTo("/cb");
    assertThat(KeycloakAdminClient.expandRedirectUri("/cb", "   ")).isEqualTo("/cb");
    assertThat(KeycloakAdminClient.expandRedirectUri("/cb/*", null)).isEqualTo("/cb/*");
  }
}
