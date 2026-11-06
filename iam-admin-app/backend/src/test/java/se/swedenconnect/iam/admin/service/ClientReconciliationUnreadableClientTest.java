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
import se.swedenconnect.iam.admin.service.model.DriftReport;
import se.swedenconnect.iam.admin.service.model.ReconciliationReport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that a client whose artifacts cannot be read, as when Authorization Services are turned off
 * for it, does not affect drift detection or reconciliation for the other clients.
 *
 * @author Martin Lindström
 */
class ClientReconciliationUnreadableClientTest {

  private static final String ORG = "2021006883";

  /** One base optional scope in the realm, so that a client holding nothing lacks one of them. */
  private static final Map<String, String> REALM = Map.of("phone", "id-phone");

  private final KeycloakAdminClient keycloakAdminClient = mock(KeycloakAdminClient.class);

  private final ClientReconciliationService service = new ClientReconciliationService(this.keycloakAdminClient);

  private final ManagedClientInfo healthy = client("healthy", Set.of("demo"));

  @BeforeEach
  void setUp() {
    when(this.keycloakAdminClient.fetchOrgFunctionTopology()).thenReturn(Map.of(ORG, Set.of("demo", "other")));
    when(this.keycloakAdminClient.fetchRealmClientScopeIds()).thenAnswer(inv -> new HashMap<>(REALM));
    when(this.keycloakAdminClient.fetchClientArtifactState(anyString())).thenAnswer(inv ->
        new ClientArtifactState(Set.of(), Set.of(), Set.of(), Set.of(), Set.of()));
    when(this.keycloakAdminClient.ensureBaseOptionalScopes(anyString(), anyMap(), any())).thenReturn(1);
    when(this.keycloakAdminClient.ensureFunctionArtifacts(anyString(), anyString(), anyString(), anyMap(), any()))
        .thenReturn(12);
  }

  /** A failing client with functions is listed once, however many pairs it has, and never counted. */
  @Test
  void detectDrift_failingClientWithFunctions_isListedOnce() {
    final ManagedClientInfo failing = this.failing(client("failing", Set.of("demo", "other")));
    when(this.keycloakAdminClient.resolveIamAdminManagedClients()).thenReturn(List.of(failing, this.healthy));

    final DriftReport drift = this.service.detectDrift();

    assertThat(drift.missingByClientId()).containsExactly(Map.entry(this.healthy.clientId(), 13));
    assertThat(drift.uncheckedClientIds()).containsExactly(failing.clientId());
    verify(this.keycloakAdminClient, times(1)).fetchClientArtifactState(failing.uuid());
  }

  /** A failing client with no functions is reported too, since the base scopes are checked on it. */
  @Test
  void detectDrift_failingClientWithoutFunctions_isListed() {
    final ManagedClientInfo failing = this.failing(client("failing", Set.of()));
    when(this.keycloakAdminClient.resolveIamAdminManagedClients()).thenReturn(List.of(this.healthy, failing));

    final DriftReport drift = this.service.detectDrift();

    assertThat(drift.missingByClientId()).containsExactly(Map.entry(this.healthy.clientId(), 13));
    assertThat(drift.uncheckedClientIds()).containsExactly(failing.clientId());
  }

  /** With every client readable, nothing is listed as unchecked. */
  @Test
  void detectDrift_allClientsReadable_noneUnchecked() {
    when(this.keycloakAdminClient.resolveIamAdminManagedClients()).thenReturn(List.of(this.healthy));

    assertThat(this.service.detectDrift().uncheckedClientIds()).isEmpty();
  }

  /** A failure that concerns every client still fails the check. */
  @Test
  void detectDrift_failureForEveryClient_propagates() {
    when(this.keycloakAdminClient.resolveIamAdminManagedClients()).thenReturn(List.of(this.healthy));
    when(this.keycloakAdminClient.fetchRealmClientScopeIds()).thenThrow(new KeycloakAdminException("down"));

    assertThatThrownBy(this.service::detectDrift).isInstanceOf(KeycloakAdminException.class);

    when(this.keycloakAdminClient.resolveIamAdminManagedClients()).thenThrow(new KeycloakAdminException("down"));
    assertThatThrownBy(this.service::detectDrift).isInstanceOf(KeycloakAdminException.class);
  }

  /**
   * A failing client with functions gives one error, and nothing more is attempted on it. The healthy
   * client is repaired.
   */
  @Test
  void reconcileAll_failingClientWithFunctions_oneErrorAndHealthyRepaired() {
    final ManagedClientInfo failing = this.failing(client("failing", Set.of("demo")));
    when(this.keycloakAdminClient.resolveIamAdminManagedClients()).thenReturn(List.of(failing, this.healthy));

    final ReconciliationReport report = this.service.reconcileAll();

    assertThat(report.errors()).singleElement().asString()
        .contains(failing.clientId())
        .contains("Authorization Services not enabled");
    assertThat(report.created()).isEqualTo(13);
    assertThat(report.clients()).isEqualTo(2);
    verify(this.keycloakAdminClient).ensureBaseOptionalScopes(eq(this.healthy.uuid()), anyMap(), any());
    verify(this.keycloakAdminClient).ensureFunctionArtifacts(eq(this.healthy.uuid()), eq(ORG), eq("demo"), anyMap(), any());
    verify(this.keycloakAdminClient).removeClientFunctionArtifacts(this.healthy.uuid(), ORG, "other", REALM);
    this.verifyNothingAttemptedOn(failing);
  }

  /** A failing client without functions, which only has removals planned, gives one error too. */
  @Test
  void reconcileAll_failingClientWithoutFunctions_oneErrorAndHealthyRepaired() {
    final ManagedClientInfo failing = this.failing(client("failing", Set.of()));
    when(this.keycloakAdminClient.resolveIamAdminManagedClients()).thenReturn(List.of(this.healthy, failing));

    final ReconciliationReport report = this.service.reconcileAll();

    assertThat(report.errors()).singleElement().asString().contains(failing.clientId());
    assertThat(report.created()).isEqualTo(13);
    verify(this.keycloakAdminClient).ensureFunctionArtifacts(eq(this.healthy.uuid()), eq(ORG), eq("demo"), anyMap(), any());
    this.verifyNothingAttemptedOn(failing);
  }

  /** A failure to read the realm client scopes still fails the run. */
  @Test
  void reconcileAll_realmScopesUnreadable_fails() {
    when(this.keycloakAdminClient.resolveIamAdminManagedClients()).thenReturn(List.of(this.healthy));
    when(this.keycloakAdminClient.fetchRealmClientScopeIds()).thenThrow(new KeycloakAdminException("down"));

    assertThatThrownBy(this.service::reconcileAll).isInstanceOf(KeycloakAdminException.class);
  }

  private ManagedClientInfo failing(final ManagedClientInfo client) {
    when(this.keycloakAdminClient.fetchClientArtifactState(client.uuid()))
        .thenThrow(new KeycloakAdminException("Authorization Services not enabled (404)"));
    return client;
  }

  private void verifyNothingAttemptedOn(final ManagedClientInfo client) {
    verify(this.keycloakAdminClient, times(1)).fetchClientArtifactState(client.uuid());
    verify(this.keycloakAdminClient, never()).ensureBaseOptionalScopes(eq(client.uuid()), anyMap(), any());
    verify(this.keycloakAdminClient, never())
        .ensureFunctionArtifacts(eq(client.uuid()), anyString(), anyString(), anyMap(), any());
    verify(this.keycloakAdminClient, never())
        .removeClientFunctionArtifacts(eq(client.uuid()), anyString(), anyString(), anyMap());
  }

  private static ManagedClientInfo client(final String name, final Set<String> functions) {
    return new ManagedClientInfo("uuid-" + name, "https://" + name + ".example.se", name,
        true, false, functions, false,
        List.of("https://" + name + ".example.se/login/oauth2/code/orgiam"),
        "https://" + name + ".example.se/jwks", null, false, true, true, true);
  }
}
