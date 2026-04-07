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
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IAM Service API controller for organization data.
 *
 * <p>Provides organization information to external applications. Data is fetched from
 * Keycloak via the service account using {@link KeycloakAdminClient}.</p>
 *
 * @author Martin Lindström
 */
@RestController
@RequestMapping("/iam-api/v1")
@RequiredArgsConstructor
@Slf4j
public class IamServiceOrganizationController {

  private final KeycloakAdminClient keycloakAdminClient;

  /**
   * Lists all organizations with their full attributes.
   *
   * @return a map keyed by organization identifier, where each value contains the
   *     organization's names, attached functions, and contact information
   */
  @GetMapping(value = "/organizations", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasRole('SUPERUSER')")
  public ResponseEntity<Map<String, OrganizationEntry>> listOrganizations() {
    log.debug("GET /iam-api/v1/organizations");

    final List<OrganizationInfo> orgs = this.keycloakAdminClient.fetchAllOrganizationGroups();

    final Map<String, OrganizationEntry> result = new LinkedHashMap<>();
    for (final OrganizationInfo org : orgs) {
      result.put(org.orgIdentifier(), new OrganizationEntry(
          org.name().get("sv"),
          org.name().get("en"),
          org.attachedFunctions(),
          org.contactEmail(),
          org.contactPhone()));
    }

    log.debug("Returning {} organizations", result.size());
    return ResponseEntity.ok(result);
  }

  record OrganizationEntry(
      @Nullable String nameSv,
      @Nullable String nameEn,
      List<String> attachedFunctions,
      @Nullable String contactEmail,
      @Nullable String contactPhone) {
  }

}
