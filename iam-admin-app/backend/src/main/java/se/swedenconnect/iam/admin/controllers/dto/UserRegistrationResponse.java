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

/**
 * The {@code iam.admin.user-registration} settings, as delivered to the frontend by
 * {@code GET /api/session}. They tell the create-user forms which fields to render and which
 * values that must be given.
 *
 * @param allowSelectUserId whether the administrator assigns the Keycloak user ID
 * @param allowTemporaryPassword whether an initial password may be set
 * @param eidAttributeRequired whether at least one eID attribute must be given
 * @param personalNumberEnabled whether a personal identity number field is offered
 * @param hsaIdEnabled whether an HSA-ID field is offered
 * @param orgAffiliationEnabled whether an organizational affiliation field is offered
 * @param efosIdEnabled whether an EFOS-ID field is offered
 * @author Martin Lindström
 */
public record UserRegistrationResponse(
    boolean allowSelectUserId,
    boolean allowTemporaryPassword,
    boolean eidAttributeRequired,
    boolean personalNumberEnabled,
    boolean hsaIdEnabled,
    boolean orgAffiliationEnabled,
    boolean efosIdEnabled) {
}
