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
 * Request body for creating a new user.
 *
 * <p>Which of the fields that are honoured is decided by
 * {@code iam.admin.user-registration}. A value for a setting that is turned off is
 * ignored.</p>
 *
 * @param name the user's full name, split into first and last name
 * @param email the user's email address
 * @param userId the Keycloak user ID (username) to assign, honoured only when
 *          {@code allow-select-user-id} is set. When absent, a random UUID is used
 * @param personalIdentityNumber the personal identity number (12 digits), honoured only when
 *          {@code personal-number-enabled} is set
 * @param orgAffiliation the organizational affiliation ({@code userID@organization-number}),
 *          honoured only when {@code org-affiliation-enabled} is set
 * @param phoneNumber the user's telephone number
 * @param temporaryPassword an initial password that Keycloak requires the user to change at first
 *          login, honoured only when {@code allow-temporary-password} is set
 * @author Martin Lindström
 */
public record CreateUserRequest(
    String name,
    String email,
    String userId,
    String personalIdentityNumber,
    String orgAffiliation,
    String phoneNumber,
    String temporaryPassword) {

  // TODO: hsaId and efosId are added here when those eID attributes are implemented.

}
