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
package se.swedenconnect.iam.admin.service.model;

import org.jspecify.annotations.NonNull;

/**
 * One (client, organization, function) combination to reconcile.
 *
 * @param clientUuid the Keycloak UUID of the client
 * @param clientId the OAuth2 client_id of the client, used in log and error messages
 * @param orgIdentifier the organization identifier
 * @param functionId the function identifier
 *
 * @author Felix Hellman
 */
public record ReconciliationTarget(
    @NonNull String clientUuid,
    @NonNull String clientId,
    @NonNull String orgIdentifier,
    @NonNull String functionId) {
}
