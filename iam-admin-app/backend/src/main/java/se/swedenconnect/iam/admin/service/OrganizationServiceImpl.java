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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import se.swedenconnect.iam.admin.controllers.dto.OrganizationPageResponse;
import se.swedenconnect.iam.admin.controllers.dto.OrganizationResponse;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;
import se.swedenconnect.iam.admin.service.cache.OrganizationCache;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Default {@link OrganizationService} implementation.
 *
 * <p>List and search operations use an {@link OrganizationCache} that is lazily primed from
 * Keycloak on first access. A service-level {@link ReentrantLock} prevents concurrent
 * Keycloak fetches during the initial prime.</p>
 *
 * @author Per Fredrik Plars
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationServiceImpl implements OrganizationService {

  private final KeycloakAdminClient keycloakAdminClient;
  private final OrganizationCache cache;

  /** Guards the lazy-prime operation so only one Keycloak fetch runs at a time. */
  private final ReentrantLock primeLock = new ReentrantLock();

  @Override
  public @NonNull OrganizationPageResponse searchByName(
      final @NonNull String query,
      final int page,
      final int size) {

    this.ensureCacheLoaded();
    final String queryLower = query.toLowerCase();

    final List<OrganizationInfo> filtered = this.cache.getAll().stream()
        .filter(o -> matchesSearch(o, queryLower))
        .sorted(Comparator.comparing(OrganizationInfo::orgIdentifier))
        .toList();

    return toPage(filtered, page, size);
  }

  @Override
  public @NonNull Optional<OrganizationInfo> findByOrgNumber(final @NonNull String orgNumber) {
    return this.keycloakAdminClient.fetchOrganizationByIdentifier(orgNumber);
  }

  @Override
  public @NonNull OrganizationPageResponse list(final int page, final int size) {
    this.ensureCacheLoaded();

    final List<OrganizationInfo> sorted = this.cache.getAll().stream()
        .sorted(Comparator.comparing(OrganizationInfo::orgIdentifier))
        .toList();

    return toPage(sorted, page, size);
  }

  @Override
  public @NonNull OrganizationPageResponse listByOrgNumbers(
      final @NonNull List<String> orgNumbers,
      final @Nullable String searchQuery,
      final int page,
      final int size) {

    this.ensureCacheLoaded();
    final Set<String> allowed = Set.copyOf(orgNumbers);
    final String queryLower = searchQuery != null ? searchQuery.toLowerCase() : null;

    final List<OrganizationInfo> filtered = this.cache.getAll().stream()
        .filter(o -> allowed.contains(o.orgIdentifier()))
        .filter(o -> queryLower == null || matchesSearch(o, queryLower))
        .sorted(Comparator.comparing(OrganizationInfo::orgIdentifier))
        .toList();

    return toPage(filtered, page, size);
  }

  @Override
  public boolean exists(final @NonNull String orgNumber) {
    return this.keycloakAdminClient.organizationExists(orgNumber);
  }

  @Override
  public boolean hasFunctionsAttached(final @NonNull String orgNumber) {
    return this.keycloakAdminClient.isFunctionAttachedToOrg_any(orgNumber);
  }

  @Override
  public @NonNull OrganizationInfo create(
      final @NonNull String orgNumber,
      final @NonNull String nameSv,
      final @NonNull String nameEn) {

    this.keycloakAdminClient.createOrganization(orgNumber, nameSv, nameEn);

    final OrganizationInfo created = this.keycloakAdminClient.fetchOrganizationByIdentifier(orgNumber)
        .orElseThrow(() -> new KeycloakAdminException(
            "Organization '" + orgNumber + "' not found immediately after creation"));

    if (this.cache.isLoaded()) {
      this.cache.put(created);
    }
    log.info("Organization '{}' created", orgNumber);
    return created;
  }

  @Override
  public @NonNull OrganizationInfo update(
      final @NonNull String orgNumber,
      final @Nullable String nameSv,
      final @Nullable String nameEn,
      final @Nullable String contactEmail,
      final @Nullable String contactPhone) {

    this.keycloakAdminClient.updateOrganization(orgNumber, nameSv, nameEn, contactEmail, contactPhone);
    this.cache.evict(orgNumber);

    final OrganizationInfo updated = this.keycloakAdminClient.fetchOrganizationByIdentifier(orgNumber)
        .orElseThrow(() -> new KeycloakAdminException(
            "Organization '" + orgNumber + "' not found immediately after update"));

    this.cache.put(updated);
    log.info("Organization '{}' updated and cache entry refreshed", orgNumber);
    return updated;
  }

  @Override
  public void delete(final @NonNull String orgNumber) {
    this.keycloakAdminClient.deleteOrganization(orgNumber);
    this.cache.evict(orgNumber);
    log.info("Organization '{}' deleted and evicted from cache", orgNumber);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void ensureCacheLoaded() {
    if (this.cache.isLoaded()) {
      return;
    }
    this.primeLock.lock();
    try {
      if (!this.cache.isLoaded()) {
        final List<OrganizationInfo> all = this.keycloakAdminClient.fetchAllOrganizationGroups();
        this.cache.prime(all);
        log.info("Organization cache primed with {} entries", all.size());
      }
    }
    finally {
      this.primeLock.unlock();
    }
  }

  private static boolean matchesSearch(final OrganizationInfo org, final String searchLower) {
    if (org.orgIdentifier().toLowerCase().contains(searchLower)) {
      return true;
    }
    final String sv = org.name().get("sv");
    if (sv != null && sv.toLowerCase().contains(searchLower)) {
      return true;
    }
    final String en = org.name().get("en");
    return en != null && en.toLowerCase().contains(searchLower);
  }

  private static @NonNull OrganizationPageResponse toPage(
      final @NonNull List<OrganizationInfo> sorted,
      final int page,
      final int size) {

    final int effectiveSize = size > 0 ? size : 50;
    final int effectivePage = Math.max(page, 0);
    final int total = sorted.size();
    final int first = effectivePage * effectiveSize;
    final int totalPages = effectiveSize > 0 ? (int) Math.ceil((double) total / effectiveSize) : 0;

    final List<OrganizationResponse> content = sorted.stream()
        .skip(first)
        .limit(effectiveSize)
        .map(OrganizationServiceImpl::toResponse)
        .toList();

    return new OrganizationPageResponse(content, total, effectivePage, effectiveSize, totalPages);
  }

  static @NonNull OrganizationResponse toResponse(final @NonNull OrganizationInfo o) {
    return new OrganizationResponse(
        o.orgIdentifier(),
        o.name().get("sv"),
        o.name().get("en"),
        o.groupId(),
        o.attachedFunctions(),
        o.contactEmail(),
        o.contactPhone());
  }
}
