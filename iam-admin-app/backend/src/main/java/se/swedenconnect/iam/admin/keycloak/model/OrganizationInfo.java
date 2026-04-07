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
package se.swedenconnect.iam.admin.keycloak.model;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import se.swedenconnect.iam.commons.types.LocalizedString;

import java.util.List;
import java.util.function.Predicate;

/**
 * Organization as represented in KeyCloak, with its attached functions.
 *
 * @author Martin Lindström
 */
public record OrganizationInfo(
    @NonNull String orgIdentifier,
    @NonNull LocalizedString name,
    @NonNull String groupId,
    @NonNull List<String> attachedFunctions,
    @Nullable String contactEmail,
    @Nullable String contactPhone) {

  /**
   * Returns a copy of this record with {@code attachedFunctions} filtered by the given predicate.
   *
   * @param functionFilter predicate that returns {@code true} for function identifiers to keep
   * @return a new {@code OrganizationInfo} with filtered attached functions
   */
  public @NonNull OrganizationInfo withFilteredFunctions(final @NonNull Predicate<String> functionFilter) {
    return new OrganizationInfo(
        this.orgIdentifier,
        this.name,
        this.groupId,
        this.attachedFunctions.stream().filter(functionFilter).toList(),
        this.contactEmail,
        this.contactPhone);
  }

}
