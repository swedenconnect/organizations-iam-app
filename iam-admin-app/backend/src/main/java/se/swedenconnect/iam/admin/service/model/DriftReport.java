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

import java.util.List;
import java.util.Map;

/**
 * The outcome of a drift check of the managed clients.
 *
 * @param missingByClientId client_id to the number of artifacts a reconciliation would create, for
 *     every client with at least one missing artifact
 * @param uncheckedClientIds the client_id of each client whose artifacts could not be read, in the
 *     order the clients were resolved; such a client never appears in {@code missingByClientId}
 *
 * @author Martin Lindström
 */
public record DriftReport(
    @NonNull Map<String, Integer> missingByClientId,
    @NonNull List<String> uncheckedClientIds) {
}
