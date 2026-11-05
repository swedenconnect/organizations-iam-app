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
package se.swedenconnect.iam.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.ClientArtifactState;
import se.swedenconnect.iam.admin.keycloak.model.ManagedClientInfo;
import se.swedenconnect.iam.admin.service.model.ReconciliationReport;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that reconciliation ensures the base optional client scopes on every client holding the
 * OIDC client role, and that drift detection reports a missing one.
 *
 * <p>The Keycloak client is mocked. Its {@code ensureBaseOptionalScopes} adds the missing scopes to
 * an in-memory client state, so that a second run sees what the first one added.</p>
 *
 * @author Martin Lindström
 */
class ClientReconciliationBaseScopesTest {

  private static final String PRIMARY = "https://id.swedenconnect.se/scope/primary-identifier";

  private static final Map<String, String> REALM = Map.of(
      "https://id.oidc.se/scope/naturalPersonNumber", "id-npn",
      "https://id.oidc.se/scope/naturalPersonOrgId", "id-orgid",
      PRIMARY, "id-primary",
      "phone", "id-phone");

  private final KeycloakAdminClient keycloakAdminClient = mock(KeycloakAdminClient.class);

  private final ClientReconciliationService service = new ClientReconciliationService(this.keycloakAdminClient);

  /** The optional scope IDs each client holds, by client UUID. */
  private final Map<String, Set<String>> held = new HashMap<>();

  private Map<String, String> realm = REALM;

  @BeforeEach
  void setUp() {
    when(this.keycloakAdminClient.fetchOrgFunctionTopology()).thenReturn(Map.of());
    when(this.keycloakAdminClient.fetchRealmClientScopeIds()).thenAnswer(inv -> new HashMap<>(this.realm));
    when(this.keycloakAdminClient.fetchClientArtifactState(anyString())).thenAnswer(inv ->
        new ClientArtifactState(Set.of(), Set.of(), Set.of(),
            Set.copyOf(this.held.getOrDefault(inv.<String>getArgument(0), Set.of())), Set.of()));
    when(this.keycloakAdminClient.ensureBaseOptionalScopes(anyString(), anyMap(), any())).thenAnswer(inv -> {
      final String uuid = inv.getArgument(0);
      final Map<String, String> realmScopeIds = inv.getArgument(1);
      final int missing = KeycloakAdminClient.countMissingBaseOptionalScopes(realmScopeIds, inv.getArgument(2));
      this.held.computeIfAbsent(uuid, k -> new HashSet<>()).addAll(realmScopeIds.values());
      return missing;
    });
  }

  /**
   * A client registered before the realm had the primary identifier scope gets it on the first run,
   * and the second run changes nothing.
   */
  @Test
  void reconcileAll_addsMissingBaseScopeOnceThenNothing() {
    final ManagedClientInfo old = client("old");
    final ManagedClientInfo complete = client("complete");
    this.held.put(old.uuid(), new HashSet<>(Set.of("id-npn", "id-orgid", "id-phone")));
    this.held.put(complete.uuid(), new HashSet<>(REALM.values()));
    when(this.keycloakAdminClient.resolveIamAdminManagedClients()).thenReturn(List.of(old, complete));

    assertThat(this.service.detectDrift()).containsExactly(Map.entry(old.clientId(), 1));

    final ReconciliationReport first = this.service.reconcileAll();
    assertThat(first.created()).isEqualTo(1);
    assertThat(first.clients()).isEqualTo(2);
    assertThat(first.errors()).isEmpty();
    assertThat(this.held.get(old.uuid())).contains("id-primary");
    assertThat(this.service.detectDrift()).isEmpty();

    final ReconciliationReport second = this.service.reconcileAll();
    assertThat(second.created()).isZero();
  }

  /** A single-client run ensures the base scopes too. */
  @Test
  void reconcileClient_addsMissingBaseScopes() {
    final ManagedClientInfo client = client("one");
    when(this.keycloakAdminClient.resolveIamAdminManagedClients()).thenReturn(List.of(client));

    final ReconciliationReport report = this.service.reconcileClient(client.clientId());

    assertThat(report.created()).isEqualTo(4);
    verify(this.keycloakAdminClient).ensureBaseOptionalScopes(eq(client.uuid()), anyMap(), any());
  }

  /**
   * A scope the realm does not have is not counted as drift and not added. Once the realm has it, the
   * next run adds it.
   */
  @Test
  void scopeMissingInRealm_isAddedOnceTheRealmHasIt() {
    final ManagedClientInfo client = client("one");
    this.held.put(client.uuid(), new HashSet<>(Set.of("id-npn", "id-orgid", "id-phone")));
    when(this.keycloakAdminClient.resolveIamAdminManagedClients()).thenReturn(List.of(client));
    this.realm = new HashMap<>(REALM);
    this.realm.remove(PRIMARY);

    assertThat(this.service.detectDrift()).isEmpty();
    final ReconciliationReport before = this.service.reconcileAll();
    assertThat(before.created()).isZero();
    assertThat(before.errors()).isEmpty();
    assertThat(this.held.get(client.uuid())).doesNotContain("id-primary");

    this.realm = REALM;
    assertThat(this.service.detectDrift()).containsExactly(Map.entry(client.clientId(), 1));
    assertThat(this.service.reconcileAll().created()).isEqualTo(1);
  }

  /** A failure on one client is reported, and the other clients are still handled. */
  @Test
  void failureOnOneClient_doesNotStopTheOthers() {
    final ManagedClientInfo broken = client("broken");
    final ManagedClientInfo fine = client("fine");
    when(this.keycloakAdminClient.resolveIamAdminManagedClients()).thenReturn(List.of(broken, fine));
    when(this.keycloakAdminClient.ensureBaseOptionalScopes(eq(broken.uuid()), anyMap(), any()))
        .thenThrow(new KeycloakAdminException("boom"));

    final ReconciliationReport report = this.service.reconcileAll();

    assertThat(report.errors()).singleElement().asString().contains(broken.clientId());
    assertThat(report.created()).isEqualTo(4);
    assertThat(this.held.get(fine.uuid())).containsAll(REALM.values());
  }

  /**
   * Only the clients holding the OIDC client role are reconciled, which is what
   * {@code resolveIamAdminManagedClients} returns. A resource server is never asked for.
   */
  @Test
  void resourceServers_areNotGivenBaseScopes() {
    when(this.keycloakAdminClient.resolveIamAdminManagedClients()).thenReturn(List.of());

    this.service.reconcileAll();

    verify(this.keycloakAdminClient, never()).resolveResourceServers();
    verify(this.keycloakAdminClient, never()).ensureBaseOptionalScopes(anyString(), anyMap(), any());
  }

  private static ManagedClientInfo client(final String name) {
    return new ManagedClientInfo("uuid-" + name, "https://" + name + ".example.se", name,
        true, false, Set.of(), false,
        List.of("https://" + name + ".example.se/login/oauth2/code/orgiam"),
        "https://" + name + ".example.se/jwks", null, false, true, true, true);
  }
}
