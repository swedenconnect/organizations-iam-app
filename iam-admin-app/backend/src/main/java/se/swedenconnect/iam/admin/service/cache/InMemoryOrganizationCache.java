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

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Heap-resident implementation of {@link OrganizationCache} backed by a {@link ConcurrentHashMap}.
 *
 * <p>Thread-safe: reads are lock-free via {@code ConcurrentHashMap}; bulk operations
 * ({@link #prime} and {@link #clear}) are serialised by an internal {@link ReentrantLock}
 * to prevent concurrent writes from corrupting the loaded flag.</p>
 *
 * @author Per Fredrik Plars
 */
@Component
@Slf4j
public class InMemoryOrganizationCache implements OrganizationCache {

  private final ConcurrentHashMap<String, OrganizationInfo> store = new ConcurrentHashMap<>();
  private final AtomicBoolean loaded = new AtomicBoolean(false);
  /** Serialises bulk write operations (prime/clear) to prevent concurrent cache corruption. */
  private final ReentrantLock writeLock = new ReentrantLock();

  @Override
  public boolean isLoaded() {
    return this.loaded.get();
  }

  @Override
  public void prime(final @NonNull Collection<OrganizationInfo> organizations) {
    this.writeLock.lock();
    try {
      this.store.clear();
      organizations.forEach(o -> this.store.put(o.orgIdentifier(), o));
      this.loaded.set(true);
      log.debug("Organization cache primed with {} entries", this.store.size());
    }
    finally {
      this.writeLock.unlock();
    }
  }

  @Override
  public @NonNull Optional<OrganizationInfo> get(final @NonNull String orgNumber) {
    return Optional.ofNullable(this.store.get(orgNumber));
  }

  @Override
  public @NonNull Collection<OrganizationInfo> getAll() {
    return Collections.unmodifiableCollection(this.store.values());
  }

  @Override
  public void put(final @NonNull OrganizationInfo org) {
    this.store.put(org.orgIdentifier(), org);
    log.debug("Organization '{}' added/updated in cache", org.orgIdentifier());
  }

  @Override
  public void evict(final @NonNull String orgNumber) {
    this.store.remove(orgNumber);
    log.debug("Organization '{}' evicted from cache", orgNumber);
  }

  @Override
  public void clear() {
    this.writeLock.lock();
    try {
      this.store.clear();
      this.loaded.set(false);
      log.debug("Organization cache cleared");
    }
    finally {
      this.writeLock.unlock();
    }
  }
}
