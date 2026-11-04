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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import se.swedenconnect.iam.admin.keycloak.model.ClientArtifactState;
import se.swedenconnect.iam.admin.keycloak.model.ManagedClientInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keycloak lookups shared by a series of {@link KeycloakAdminClient#attachFunctionToOrg(String, String,
 * FunctionAttachContext)} calls.
 *
 * <p>Attaching a function reads the managed clients, every realm client scope and every
 * Authorization Services artifact of each client. All of these grow with the number of
 * organizations, so a bulk import that read them again for every attachment would slow down with
 * every organization it created. A context reads each of them once, on first use, and keeps them
 * up to date with what the attachments made through it create.</p>
 *
 * <p>A context assumes that it is the only writer while it is in use. It is not thread-safe, and is
 * meant to live no longer than one bulk operation.</p>
 *
 * @author PF Plars
 */
public final class FunctionAttachContext {

  /** Organization identifier to Keycloak group UUID. */
  private final Map<String, String> orgGroupIds = new HashMap<>();

  /** Client UUID to the artifacts the client holds. */
  private final Map<String, ClientArtifactState> clientStates = new HashMap<>();

  private @Nullable List<ManagedClientInfo> managedClients;

  private @Nullable Map<String, String> realmScopeIds;

  /**
   * Records the Keycloak group UUID of an organization, so that attaching a function to it does
   * not have to look the organization up.
   *
   * @param orgIdentifier the organization identifier
   * @param groupId the Keycloak group UUID of the organization
   */
  public void putOrgGroupId(final @NonNull String orgIdentifier, final @NonNull String groupId) {
    this.orgGroupIds.put(orgIdentifier, groupId);
  }

  @Nullable String getOrgGroupId(final @NonNull String orgIdentifier) {
    return this.orgGroupIds.get(orgIdentifier);
  }

  @Nullable List<ManagedClientInfo> getManagedClients() {
    return this.managedClients;
  }

  void setManagedClients(final @NonNull List<ManagedClientInfo> managedClients) {
    this.managedClients = managedClients;
  }

  @Nullable Map<String, String> getRealmScopeIds() {
    return this.realmScopeIds;
  }

  void setRealmScopeIds(final @NonNull Map<String, String> realmScopeIds) {
    this.realmScopeIds = realmScopeIds;
  }

  @Nullable ClientArtifactState getClientState(final @NonNull String clientUuid) {
    return this.clientStates.get(clientUuid);
  }

  void putClientState(final @NonNull String clientUuid, final @NonNull ClientArtifactState state) {
    this.clientStates.put(clientUuid, state);
  }

}
