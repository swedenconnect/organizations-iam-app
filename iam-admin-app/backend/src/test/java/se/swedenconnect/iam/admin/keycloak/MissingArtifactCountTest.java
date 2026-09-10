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
import se.swedenconnect.iam.admin.keycloak.model.ClientArtifactState;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link KeycloakAdminClient#countMissingFunctionArtifacts}, the dry-run counterpart of
 * {@link KeycloakAdminClient#ensureFunctionArtifacts}.
 *
 * <p>The two have to agree: a pair counted as {@code 0} is one a reconciliation leaves untouched,
 * and the count is what the Services tab warns with.</p>
 *
 * @author Felix Hellman
 */
class MissingArtifactCountTest {

  private static final String ORG = "5590026042";
  private static final String FUNCTION = "swedenconnect";

  /** Three right levels, four artifact types each. */
  private static final int FULL_SET = 12;

  @Test
  @DisplayName("A client holding nothing is missing the whole set")
  void emptyClientMissesEverything() {
    assertThat(KeycloakAdminClient.countMissingFunctionArtifacts(
        ORG, FUNCTION, realmScopeIds(), empty()))
        .isEqualTo(FULL_SET);
  }

  @Test
  @DisplayName("A fully provisioned client is missing nothing")
  void provisionedClientMissesNothing() {
    assertThat(KeycloakAdminClient.countMissingFunctionArtifacts(
        ORG, FUNCTION, realmScopeIds(), complete()))
        .isZero();
  }

  @Test
  @DisplayName("A client holding only its authz scopes is missing the other three per level")
  void authzScopesOnly() {
    // The state the IAM Admin application was actually found in: the authz scopes existed, the
    // policies, permissions and optional client scope bindings did not.
    final ClientArtifactState state = new ClientArtifactState(
        scopeNames(), Set.of(), Set.of(), Set.of());

    assertThat(KeycloakAdminClient.countMissingFunctionArtifacts(
        ORG, FUNCTION, realmScopeIds(), state))
        .isEqualTo(9);
  }

  @Test
  @DisplayName("Two organizations in that state come to the 18 the live run reported")
  void twoOrganizationsMatchTheObservedRun() {
    final ClientArtifactState state = new ClientArtifactState(
        Set.of(
            KeycloakAdminClient.scopeName("5590026042", FUNCTION, "read"),
            KeycloakAdminClient.scopeName("5590026042", FUNCTION, "write"),
            KeycloakAdminClient.scopeName("5590026042", FUNCTION, "admin"),
            KeycloakAdminClient.scopeName("5594020496", FUNCTION, "read"),
            KeycloakAdminClient.scopeName("5594020496", FUNCTION, "write"),
            KeycloakAdminClient.scopeName("5594020496", FUNCTION, "admin")),
        Set.of(), Set.of(), Set.of());

    final int total =
        KeycloakAdminClient.countMissingFunctionArtifacts("5590026042", FUNCTION, Map.of(), state)
            + KeycloakAdminClient.countMissingFunctionArtifacts("5594020496", FUNCTION, Map.of(), state);

    assertThat(total).isEqualTo(18);
  }

  @Test
  @DisplayName("An unknown realm client scope means the optional binding cannot exist")
  void missingRealmScopeCountsTheBinding() {
    // Everything on the client is present, but the realm client scope the binding points at is
    // not, so the binding is missing: one per right level.
    final ClientArtifactState state = new ClientArtifactState(
        scopeNames(), policyNames(), permissionNames(), Set.of());

    assertThat(KeycloakAdminClient.countMissingFunctionArtifacts(
        ORG, FUNCTION, Map.of(), state))
        .isEqualTo(3);
  }

  private static Map<String, String> realmScopeIds() {
    return Map.of(
        KeycloakAdminClient.scopeName(ORG, FUNCTION, "read"), "id-read",
        KeycloakAdminClient.scopeName(ORG, FUNCTION, "write"), "id-write",
        KeycloakAdminClient.scopeName(ORG, FUNCTION, "admin"), "id-admin");
  }

  private static ClientArtifactState empty() {
    return new ClientArtifactState(Set.of(), Set.of(), Set.of(), Set.of());
  }

  private static ClientArtifactState complete() {
    return new ClientArtifactState(
        scopeNames(), policyNames(), permissionNames(), Set.of("id-read", "id-write", "id-admin"));
  }

  private static Set<String> scopeNames() {
    return Set.of(
        KeycloakAdminClient.scopeName(ORG, FUNCTION, "read"),
        KeycloakAdminClient.scopeName(ORG, FUNCTION, "write"),
        KeycloakAdminClient.scopeName(ORG, FUNCTION, "admin"));
  }

  private static Set<String> policyNames() {
    return Set.of(
        KeycloakAdminClient.policyName(ORG, FUNCTION, "read"),
        KeycloakAdminClient.policyName(ORG, FUNCTION, "write"),
        KeycloakAdminClient.policyName(ORG, FUNCTION, "admin"));
  }

  private static Set<String> permissionNames() {
    return Set.of(
        KeycloakAdminClient.permissionName(ORG, FUNCTION, "read"),
        KeycloakAdminClient.permissionName(ORG, FUNCTION, "write"),
        KeycloakAdminClient.permissionName(ORG, FUNCTION, "admin"));
  }
}
