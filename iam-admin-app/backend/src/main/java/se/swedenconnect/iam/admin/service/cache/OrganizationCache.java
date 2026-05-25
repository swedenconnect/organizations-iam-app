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
package se.swedenconnect.iam.admin.service.cache;

import org.jspecify.annotations.NonNull;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;

import java.util.Collection;
import java.util.Optional;

/**
 * Cache abstraction for {@link OrganizationInfo} objects.
 *
 * <p>The current in-memory implementation uses a {@link java.util.concurrent.ConcurrentHashMap}.
 * A future Redis implementation can fulfil this contract without changing any callers.</p>
 *
 * @author Per Fredrik Plars
 */
public interface OrganizationCache {

  /**
   * Returns {@code true} if the cache has been primed with data from Keycloak.
   *
   * @return {@code true} if loaded
   */
  boolean isLoaded();

  /**
   * Clears existing entries and populates the cache with the supplied organizations.
   *
   * @param organizations the organizations to prime with
   */
  void prime(@NonNull Collection<OrganizationInfo> organizations);

  /**
   * Returns the cached entry for the given org number, if present.
   *
   * @param orgNumber the organization identifier
   * @return an Optional containing the org, or empty if not cached
   */
  @NonNull Optional<OrganizationInfo> get(@NonNull String orgNumber);

  /**
   * Returns all cached organizations as an unmodifiable collection.
   *
   * @return unmodifiable view of the cache contents
   */
  @NonNull Collection<OrganizationInfo> getAll();

  /**
   * Inserts or replaces a single organization in the cache.
   *
   * @param org the organization to store
   */
  void put(@NonNull OrganizationInfo org);

  /**
   * Removes the organization with the given identifier from the cache.
   *
   * @param orgNumber the organization identifier to evict
   */
  void evict(@NonNull String orgNumber);

  /**
   * Clears all cached data and resets the loaded flag so the next access triggers a fresh prime.
   */
  void clear();
}
