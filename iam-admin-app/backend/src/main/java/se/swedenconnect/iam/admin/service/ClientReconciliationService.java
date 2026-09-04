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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.ClientArtifactState;
import se.swedenconnect.iam.admin.keycloak.model.ManagedClientInfo;
import se.swedenconnect.iam.admin.service.model.ReconciliationPlan;
import se.swedenconnect.iam.admin.service.model.ReconciliationReport;
import se.swedenconnect.iam.admin.service.model.ReconciliationTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Brings the KeyCloak artifacts of managed clients in line with the org/function topology.
 *
 * <p>For every managed client, and every organization that has one of the client's functions
 * attached, the client must hold three OAuth2 client scopes, three Authorization Services scopes,
 * three group policies, three scope permissions, and three optional client scope assignments.
 * Reconciliation creates whatever is missing.</p>
 *
 * <p>The operation is idempotent, so a run that finds nothing missing makes no changes. It exists
 * to repair drift — clients registered after functions were already attached, partial failures
 * (artifact creation performs no rollback), and manual edits in the KeyCloak admin console.</p>
 *
 * <p>A client handles exactly the functions it declares; declaring none means it receives
 * nothing.</p>
 *
 * <p>Pruning is opt-in. With pruning enabled, artifacts belonging to org/function combinations the
 * client no longer handles are removed as well; without it, narrowing a client's
 * {@code client_functions} stops new artifacts from being created but leaves existing ones
 * in place.</p>
 *
 * @author Felix Hellman
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClientReconciliationService {

  private final KeycloakAdminClient keycloakAdminClient;

  /**
   * Reconciles every managed client.
   *
   * @return the reconciliation report; never {@code null}
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public @NonNull ReconciliationReport reconcileAll() {
    return this.reconcile(this.keycloakAdminClient.resolveIamAdminManagedClients());
  }

  /**
   * Reconciles a single managed client.
   *
   * @param clientId the OAuth2 client_id of the client
   * @return the reconciliation report; never {@code null}
   * @throws KeycloakAdminException if the client is not managed, or on any Keycloak API error
   */
  public @NonNull ReconciliationReport reconcileClient(final @NonNull String clientId) {

    final ManagedClientInfo client = this.keycloakAdminClient.resolveIamAdminManagedClients().stream()
        .filter(c -> clientId.equals(c.clientId()))
        .findFirst()
        .orElseThrow(() -> new KeycloakAdminException("Not a managed Keycloak client: " + clientId));
    return this.reconcile(List.of(client));
  }

  /**
   * Reconciles the given managed clients against the current org/function topology.
   *
   * @param clients the managed clients
   * @return the reconciliation report; never {@code null}
   * @throws KeycloakAdminException on any Keycloak API error
   */
  private @NonNull ReconciliationReport reconcile(final @NonNull List<ManagedClientInfo> clients) {

    final Map<String, Set<String>> topology = this.keycloakAdminClient.fetchOrgFunctionTopology();
    final ReconciliationPlan plan = plan(clients, topology);

    for (final ManagedClientInfo client : clients) {
      if (client.unscoped()) {
        log.warn("Managed client '{}' declares no functions — it receives no scopes, policies or"
            + " permissions. Assign functions to it.", client.clientId());
      }
    }

    return this.apply(plan);
  }

  /**
   * Computes the artifacts that should exist, and those that should not.
   *
   * <p>Pure function of the two snapshots: no KeyCloak calls are made.</p>
   *
   * @param clients the managed clients
   * @param topology organization identifier to the functions attached to it
   * @return the plan; never {@code null}
   */
  public static @NonNull ReconciliationPlan plan(
      final @NonNull List<ManagedClientInfo> clients,
      final @NonNull Map<String, Set<String>> topology) {

    final List<ReconciliationTarget> ensure = new ArrayList<>();
    final List<ReconciliationTarget> remove = new ArrayList<>();

    for (final ManagedClientInfo client : clients) {
      for (final Map.Entry<String, Set<String>> org : topology.entrySet()) {
        for (final String functionId : org.getValue()) {
          final ReconciliationTarget target =
              new ReconciliationTarget(client.uuid(), client.clientId(), org.getKey(), functionId);
          if (client.handles(functionId)) {
            ensure.add(target);
          }
          else {
            remove.add(target);
          }
        }
      }
    }
    return new ReconciliationPlan(List.copyOf(ensure), List.copyOf(remove));
  }

  /**
   * Applies a plan, creating missing artifacts and — when the plan carries removals — deleting the
   * ones that should no longer exist.
   *
   * <p>A failure on one target is recorded in the report and does not abort the run: a single
   * broken client must not stop the others from being repaired.</p>
   *
   * @param plan the plan to apply
   * @return the reconciliation report; never {@code null}
   * @throws KeycloakAdminException if the realm client scopes cannot be read
   */
  public @NonNull ReconciliationReport apply(final @NonNull ReconciliationPlan plan) {
    final Map<String, String> realmScopeIds = this.keycloakAdminClient.fetchRealmClientScopeIds();
    final Map<String, ClientArtifactState> states = new LinkedHashMap<>();
    final List<String> errors = new ArrayList<>();
    int created = 0;
    int removed = 0;

    for (final ReconciliationTarget target : plan.ensure()) {
      final ClientArtifactState state = states.computeIfAbsent(
          target.clientUuid(), this.keycloakAdminClient::fetchClientArtifactState);
      final Integer count = this.attempt(errors, target, "create",
          () -> this.keycloakAdminClient.ensureFunctionArtifacts(
              target.clientUuid(), target.orgIdentifier(), target.functionId(), realmScopeIds, state));
      if (count != null) {
        created += count;
      }
    }

    for (final ReconciliationTarget target : plan.remove()) {
      final Integer count = this.attempt(errors, target, "remove",
          () -> this.keycloakAdminClient.removeClientFunctionArtifacts(
              target.clientUuid(), target.orgIdentifier(), target.functionId(), realmScopeIds));
      if (count != null) {
        removed += count;
      }
    }

    final ReconciliationReport report = new ReconciliationReport(
        plan.clientCount(), created, removed, List.copyOf(errors));

    if (created > 0 || removed > 0 || !errors.isEmpty()) {
      log.info("Client reconciliation: {} artifacts created, {} removed across {} clients, {} errors",
          created, removed, report.clients(), errors.size());
    }
    else {
      log.debug("Client reconciliation: nothing to do for {} clients", report.clients());
    }
    return report;
  }

  /**
   * Runs one artifact operation, recording a failure instead of propagating it.
   *
   * @param errors the error list to append to
   * @param target the target being processed
   * @param operation the operation name, used in the error message
   * @param action the operation
   * @return the number of artifacts touched, or {@code null} if the operation failed
   */
  private @Nullable Integer attempt(
      final @NonNull List<String> errors,
      final @NonNull ReconciliationTarget target,
      final @NonNull String operation,
      final @NonNull ArtifactOperation action) {

    try {
      return action.run();
    }
    catch (final KeycloakAdminException e) {
      final String message = "Failed to %s artifacts for '%s:%s' on client '%s': %s".formatted(
          operation, target.orgIdentifier(), target.functionId(), target.clientId(), e.getMessage());
      log.warn(message);
      errors.add(message);
      return null;
    }
  }

  /** One artifact create or remove operation, returning the number of artifacts touched. */
  @FunctionalInterface
  private interface ArtifactOperation {

    /**
     * Runs the operation.
     *
     * @return the number of artifacts touched
     * @throws KeycloakAdminException on any Keycloak API error
     */
    int run();
  }
}
