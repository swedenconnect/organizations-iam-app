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

/**
 * The outcome of a reconciliation run.
 *
 * @param clients the number of clients the run covered
 * @param created the number of KeyCloak artifacts created
 * @param removed the number of KeyCloak artifacts removed
 * @param errors one message per (client, organization, function) combination that failed; the run
 *     continues past a failure so that one broken client does not block the others
 *
 * @author Felix Hellman
 */
public record ReconciliationReport(
    int clients,
    int created,
    int removed,
    @NonNull List<String> errors) {
}
