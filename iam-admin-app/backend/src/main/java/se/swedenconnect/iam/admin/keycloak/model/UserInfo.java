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

import java.util.List;
import java.util.function.Predicate;

/**
 * User profile as loaded from the KeyCloak Admin REST API.
 *
 * @author Martin Lindström
 */
public record UserInfo(
    @NonNull String userId,
    @Nullable String username,
    @Nullable String firstName,
    @Nullable String lastName,
    @Nullable String email,
    @Nullable String personalIdentityNumber,
    @Nullable String phoneNumber,
    boolean superuser,
    @NonNull List<UserRight> rights) {

  /**
   * Returns a copy of this record with {@code rights} filtered by the given predicate.
   *
   * @param rightsFilter predicate that returns {@code true} for rights to keep
   * @return a new {@code UserInfo} with filtered rights
   */
  public @NonNull UserInfo withFilteredRights(final @NonNull Predicate<UserRight> rightsFilter) {
    return new UserInfo(
        this.userId,
        this.username,
        this.firstName,
        this.lastName,
        this.email,
        this.personalIdentityNumber,
        this.phoneNumber,
        this.superuser,
        this.rights.stream().filter(rightsFilter).toList());
  }

}
