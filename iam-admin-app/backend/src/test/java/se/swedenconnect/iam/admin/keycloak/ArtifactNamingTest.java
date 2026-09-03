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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test cases for the KeyCloak artifact naming used by {@link KeycloakAdminClient}.
 *
 * <p>The names are the contract between artifact creation and artifact removal — a mismatch leaves
 * artifacts behind that no cleanup path can find. They are also documented in
 * {@code docs/keycloak-setup.md}.</p>
 *
 * @author Felix Hellman
 */
class ArtifactNamingTest {

  @Test
  @DisplayName("Scope names are colon-separated")
  void scopeNamesAreColonSeparated() {
    assertEquals("5590026042:demo:read", KeycloakAdminClient.scopeName("5590026042", "demo", "read"));
  }

  @Test
  @DisplayName("Policy names are dash-separated, as documented")
  void policyNamesAreDashSeparated() {
    assertEquals("policy-5590026042-demo-read",
        KeycloakAdminClient.policyName("5590026042", "demo", "read"));
  }

  @Test
  @DisplayName("Permission names are dash-separated, as documented")
  void permissionNamesAreDashSeparated() {
    assertEquals("permission-5590026042-demo-read",
        KeycloakAdminClient.permissionName("5590026042", "demo", "read"));
  }

  @Test
  @DisplayName("Rights levels are read, write and admin")
  void rightsLevels() {
    assertEquals(List.of("read", "write", "admin"), KeycloakAdminClient.RIGHT_LEVELS);
  }
}
