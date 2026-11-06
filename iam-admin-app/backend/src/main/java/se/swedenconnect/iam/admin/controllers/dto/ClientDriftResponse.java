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
package se.swedenconnect.iam.admin.controllers.dto;

import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;

/**
 * The clients whose KeyCloak artifacts are not what the org/function topology calls for.
 *
 * <p>A client appears here when a reconciliation run would create something for it. Until that run
 * happens the client is missing scopes, policies, permissions or optional client scope bindings for
 * the organizations concerned, and no user can obtain an org-scoped token from it.</p>
 *
 * <p>A client whose artifacts could not be read is listed in {@code uncheckedClientIds} instead.
 * It is not known whether such a client holds what it should, so it must not be shown as correctly
 * configured.</p>
 *
 * @param missingByClientId the OAuth2 client_id of each affected client, mapped to the number of
 *     artifacts a reconciliation would create for it; empty when nothing has drifted
 * @param uncheckedClientIds the OAuth2 client_id of each client that could not be checked, each
 *     listed once; empty when every client was checked
 *
 * @author Felix Hellman
 */
public record ClientDriftResponse(
    @NonNull Map<String, Integer> missingByClientId,
    @NonNull List<String> uncheckedClientIds) {
}
