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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import se.swedenconnect.iam.admin.controllers.dto.OrganizationPageResponse;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;

import java.util.List;
import java.util.Optional;

/**
 * Service for organization data retrieval and mutation.
 *
 * <p>Search and list operations read from an in-process cache that is lazily primed on first
 * access. Write operations delegate to Keycloak and keep the cache coherent.</p>
 *
 * @author Per Fredrik Plars
 */
public interface OrganizationService {

  /**
   * Searches for organizations whose name or identifier contains {@code query} (case-insensitive).
   * Primes the cache on first call.
   *
   * @param query the search term
   * @param page  0-based page index
   * @param size  maximum items per page
   * @return paginated result sorted by org identifier
   */
  @NonNull OrganizationPageResponse searchByName(@NonNull String query, int page, int size);

  /**
   * Fetches a single organization directly from Keycloak, bypassing the cache.
   *
   * @param orgNumber the organization identifier
   * @return the organization, or empty if not found
   */
  @NonNull Optional<OrganizationInfo> findByOrgNumber(@NonNull String orgNumber);

  /**
   * Lists all organizations from the cache sorted by org identifier. Primes the cache on first call.
   *
   * @param page 0-based page index
   * @param size maximum items per page
   * @return paginated result
   */
  @NonNull OrganizationPageResponse list(int page, int size);

  /**
   * Returns organizations whose identifiers are contained in {@code orgNumbers}, optionally
   * filtered by a case-insensitive substring match on name or identifier.
   * Primes the cache on first call.
   *
   * @param orgNumbers  the allowed organization identifiers
   * @param searchQuery optional substring filter; {@code null} returns all matches
   * @param page        0-based page index
   * @param size        maximum items per page
   * @return paginated result sorted by org identifier
   */
  @NonNull OrganizationPageResponse listByOrgNumbers(
      @NonNull List<String> orgNumbers,
      @Nullable String searchQuery,
      int page,
      int size);

  /**
   * Returns {@code true} if an organization with the given identifier exists in Keycloak.
   *
   * @param orgNumber the organization identifier
   * @return {@code true} if the organization exists
   */
  boolean exists(@NonNull String orgNumber);

  /**
   * Returns {@code true} if the organization has any functions attached.
   *
   * @param orgNumber the organization identifier
   * @return {@code true} if at least one function is attached
   */
  boolean hasFunctionsAttached(@NonNull String orgNumber);

  /**
   * Creates a new organization in Keycloak and adds it to the cache if the cache is already loaded.
   *
   * @param orgNumber the 10-digit organization number
   * @param nameSv    Swedish name
   * @param nameEn    English name
   * @return the freshly created organization
   */
  @NonNull OrganizationInfo create(@NonNull String orgNumber, @NonNull String nameSv, @NonNull String nameEn);

  /**
   * Updates an organization in Keycloak and refreshes the corresponding cache entry.
   *
   * @param orgNumber    the organization identifier
   * @param nameSv       new Swedish name, or {@code null} to leave unchanged
   * @param nameEn       new English name, or {@code null} to leave unchanged
   * @param contactEmail new contact e-mail, or {@code null} to leave unchanged
   * @param contactPhone new contact phone, or {@code null} to leave unchanged
   * @return the updated organization as returned by Keycloak
   */
  @NonNull OrganizationInfo update(
      @NonNull String orgNumber,
      @Nullable String nameSv,
      @Nullable String nameEn,
      @Nullable String contactEmail,
      @Nullable String contactPhone);

  /**
   * Deletes an organization from Keycloak and evicts it from the cache.
   *
   * @param orgNumber the organization identifier
   */
  void delete(@NonNull String orgNumber);
}
