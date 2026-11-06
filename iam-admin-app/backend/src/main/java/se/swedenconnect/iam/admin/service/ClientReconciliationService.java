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
import se.swedenconnect.iam.admin.service.model.DriftReport;
import se.swedenconnect.iam.admin.service.model.ReconciliationPlan;
import se.swedenconnect.iam.admin.service.model.ReconciliationReport;
import se.swedenconnect.iam.admin.service.model.ReconciliationTarget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Brings the KeyCloak artifacts of managed clients in line with the org/function topology.
 *
 * <p>For every managed client, and every organization that has one of the client's functions
 * attached, the client must hold three OAuth2 client scopes, three Authorization Services scopes,
 * three group policies, three scope permissions, and three optional client scope assignments.
 * Every managed client must also hold the base optional client scopes, see
 * {@code KeycloakAdminClient.BASE_OPTIONAL_SCOPES}. Reconciliation creates whatever is missing,
 * so a base scope removed from a client by hand comes back on the next run.</p>
 *
 * <p>The operation is idempotent, so a run that finds nothing missing makes no changes. It exists
 * to repair drift — clients registered after functions were already attached, partial failures
 * (artifact creation performs no rollback), and manual edits in the KeyCloak admin console.</p>
 *
 * <p>A client handles exactly the functions it declares; declaring none means it receives
 * nothing. The exception is a client marked as handling all functions: it receives artifacts for
 * every org/function pair in the topology, and never has any of them pruned.</p>
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

    return this.apply(plan, clients);
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
   * Reports, per client, how many artifacts a reconciliation run would create, without creating
   * anything.
   *
   * <p>Drift is normal after a client's markers or functions are changed outside the application,
   * because setting {@code client_functions} or {@code iam_admin_all_functions} decides what a
   * client <em>should</em> hold and creates nothing. Until a run happens the client holds no
   * scopes, policies, permissions or optional client scope bindings for the affected
   * organizations, and no user can obtain an org-scoped token from it. Surfacing the count lets
   * the Services tab say so rather than showing a client that looks correctly configured.</p>
   *
   * <p>A base optional client scope the client lacks counts as missing too, when the realm has it.
   * A client without it cannot release the claims the scope carries.</p>
   *
   * <p>Removals are deliberately not counted. They are not a reason to warn: a client that holds
   * more than it should still works.</p>
   *
   * <p>A client whose artifacts cannot be read, for example because Authorization Services are
   * turned off for it, is reported as unchecked and does not affect the result for the others. It
   * is not left out, because that would make it look correctly configured.</p>
   *
   * @return the clients with at least one missing artifact, and the clients that could not be
   *     checked; never {@code null}
   * @throws KeycloakAdminException if the clients, the topology or the realm client scopes cannot
   *     be read
   */
  public @NonNull DriftReport detectDrift() {
    final List<ManagedClientInfo> clients = this.keycloakAdminClient.resolveIamAdminManagedClients();
    final Map<String, Set<String>> topology = this.keycloakAdminClient.fetchOrgFunctionTopology();
    final ReconciliationPlan plan = plan(clients, topology);

    final Map<String, String> realmScopeIds = this.keycloakAdminClient.fetchRealmClientScopeIds();
    final ClientStates states = new ClientStates(new ArrayList<>());
    final Map<String, Integer> drift = new LinkedHashMap<>();

    for (final ManagedClientInfo client : clients) {
      final ClientArtifactState state = states.get(client.uuid(), client.clientId());
      if (state == null) {
        continue;
      }
      final int missing = KeycloakAdminClient.countMissingBaseOptionalScopes(realmScopeIds, state);
      if (missing > 0) {
        drift.merge(client.clientId(), missing, Integer::sum);
      }
    }

    for (final ReconciliationTarget target : plan.ensure()) {
      final ClientArtifactState state = states.get(target.clientUuid(), target.clientId());
      if (state == null) {
        continue;
      }
      final int missing = KeycloakAdminClient.countMissingFunctionArtifacts(
          target.orgIdentifier(), target.functionId(), realmScopeIds, state);
      if (missing > 0) {
        drift.merge(target.clientId(), missing, Integer::sum);
      }
    }

    if (!drift.isEmpty()) {
      log.debug("Clients with missing artifacts: {}", drift);
    }
    return new DriftReport(drift, states.unreadableClientIds());
  }

  /**
   * Applies a plan, creating missing artifacts and — when the plan carries removals — deleting the
   * ones that should no longer exist. Every client in {@code clients} is also given the base
   * optional client scopes it lacks, whether or not the plan has work for it.
   *
   * <p>A failure on one target is recorded in the report and does not abort the run: a single
   * broken client must not stop the others from being repaired. A client whose artifacts cannot be
   * read is recorded once and skipped for the rest of the run. A base scope the realm does not
   * have is logged and skipped.</p>
   *
   * @param plan the plan to apply
   * @param clients the managed clients the plan was computed for
   * @return the reconciliation report; never {@code null}
   * @throws KeycloakAdminException if the realm client scopes cannot be read
   */
  public @NonNull ReconciliationReport apply(
      final @NonNull ReconciliationPlan plan, final @NonNull List<ManagedClientInfo> clients) {
    final Map<String, String> realmScopeIds = this.keycloakAdminClient.fetchRealmClientScopeIds();
    final List<String> errors = new ArrayList<>();
    final ClientStates states = new ClientStates(errors);
    int created = 0;
    int removed = 0;

    if (!clients.isEmpty()) {
      KeycloakAdminClient.warnMissingBaseOptionalScopes(realmScopeIds);
    }
    for (final ManagedClientInfo client : clients) {
      final ClientArtifactState state = states.get(client.uuid(), client.clientId());
      if (state == null) {
        continue;
      }
      try {
        created += this.keycloakAdminClient.ensureBaseOptionalScopes(client.uuid(), realmScopeIds, state);
      }
      catch (final KeycloakAdminException e) {
        final String message = "Failed to add base optional client scopes on client '%s': %s".formatted(
            client.clientId(), e.getMessage());
        log.warn(message);
        errors.add(message);
      }
    }

    for (final ReconciliationTarget target : plan.ensure()) {
      final ClientArtifactState state = states.get(target.clientUuid(), target.clientId());
      if (state == null) {
        continue;
      }
      final Integer count = this.attempt(errors, target, "create",
          () -> this.keycloakAdminClient.ensureFunctionArtifacts(
              target.clientUuid(), target.orgIdentifier(), target.functionId(), realmScopeIds, state));
      if (count != null) {
        created += count;
      }
    }

    for (final ReconciliationTarget target : plan.remove()) {
      if (states.isUnreadable(target.clientUuid())) {
        continue;
      }
      final Integer count = this.attempt(errors, target, "remove",
          () -> this.keycloakAdminClient.removeClientFunctionArtifacts(
              target.clientUuid(), target.orgIdentifier(), target.functionId(), realmScopeIds));
      if (count != null) {
        removed += count;
      }
    }

    final int clientCount = (int) Stream.concat(
            clients.stream().map(ManagedClientInfo::uuid),
            Stream.concat(plan.ensure().stream(), plan.remove().stream()).map(ReconciliationTarget::clientUuid))
        .distinct()
        .count();
    final ReconciliationReport report = new ReconciliationReport(
        clientCount, created, removed, List.copyOf(errors));

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

  /**
   * Reads each client's artifact state once, and remembers the clients that could not be read so
   * that a failed read is logged and reported once per client.
   */
  private final class ClientStates {

    /** The states read so far, by client UUID. */
    private final Map<String, ClientArtifactState> states = new HashMap<>();

    /** The client_id of each client that could not be read, by client UUID. */
    private final Map<String, String> unreadable = new LinkedHashMap<>();

    /** Where a failed read is reported. */
    private final List<String> errors;

    /**
     * Constructor.
     *
     * @param errors the list a failed read is added to
     */
    ClientStates(final @NonNull List<String> errors) {
      this.errors = errors;
    }

    /**
     * Returns the artifact state of a client, reading it on first use.
     *
     * @param clientUuid the Keycloak UUID of the client
     * @param clientId the OAuth2 client_id of the client, used in the error message
     * @return the client's state, or {@code null} if it could not be read
     */
    @Nullable ClientArtifactState get(final @NonNull String clientUuid, final @NonNull String clientId) {
      if (this.unreadable.containsKey(clientUuid)) {
        return null;
      }
      final ClientArtifactState cached = this.states.get(clientUuid);
      if (cached != null) {
        return cached;
      }
      try {
        final ClientArtifactState state =
            ClientReconciliationService.this.keycloakAdminClient.fetchClientArtifactState(clientUuid);
        this.states.put(clientUuid, state);
        return state;
      }
      catch (final KeycloakAdminException e) {
        final String message = "Failed to read the artifacts of client '%s': %s".formatted(clientId, e.getMessage());
        log.warn(message);
        this.errors.add(message);
        this.unreadable.put(clientUuid, clientId);
        return null;
      }
    }

    /**
     * Tells whether a client has been found unreadable.
     *
     * @param clientUuid the Keycloak UUID of the client
     * @return {@code true} if a read of the client has failed
     */
    boolean isUnreadable(final @NonNull String clientUuid) {
      return this.unreadable.containsKey(clientUuid);
    }

    /**
     * Returns the client_id of each client that could not be read.
     *
     * @return the client IDs, in the order the reads failed; never {@code null}
     */
    @NonNull List<String> unreadableClientIds() {
      return List.copyOf(this.unreadable.values());
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
