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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;
import se.swedenconnect.iam.admin.keycloak.model.RightsHolderEntry;
import se.swedenconnect.iam.security.claims.InsufficientRightsException;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;
import se.swedenconnect.iam.security.claims.OrgRightsClaimParser;
import org.springframework.security.oauth2.jwt.Jwt;
import com.fasterxml.jackson.annotation.JsonProperty;

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
  private final OrgRightsClaimParser claimParser;

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
          new OrganizationEntry.Contact(org.contactEmail(), org.contactPhone())));
    }

    log.debug("Returning {} organizations", result.size());
    return ResponseEntity.ok(result);
  }

  record OrganizationEntry(
      @JsonProperty("name#sv") @Nullable String nameSv,
      @JsonProperty("name#en") @Nullable String nameEn,
      @JsonProperty("attached_functions") @NonNull List<String> attachedFunctions,
      @JsonProperty("contact") @NonNull Contact contact) {

    record Contact(@Nullable String email, @Nullable String phone) {
    }
  }

  /**
   * Lists all users holding a right on a specific (organization, function) combination.
   *
   * @param orgIdentifier the organization identifier
   * @param functionId    the function identifier
   * @param jwt           the caller's JWT
   * @return the list of rights holders
   */
  @GetMapping(
      value = "/organizations/{orgIdentifier}/functions/{functionId}/users",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> listRightsHolders(
      @PathVariable final @NonNull String orgIdentifier,
      @PathVariable final @NonNull String functionId,
      @AuthenticationPrincipal final @NonNull Jwt jwt) {

    log.debug("GET /iam-api/v1/organizations/{}/functions/{}/users", orgIdentifier, functionId);

    // Authorization: superuser OR admin on (org, function)
    final boolean isSuperuser = SecurityContextHolder.getContext().getAuthentication()
        .getAuthorities().contains(new SimpleGrantedAuthority("ROLE_SUPERUSER"));

    if (!isSuperuser) {
      final Object rawClaim = jwt.getClaim("org_rights");
      final OrgRightsClaim claim = this.claimParser.parse(rawClaim);
      try {
        this.claimParser.checkAdminConstraint(claim, orgIdentifier, functionId);
      }
      catch (final InsufficientRightsException e) {
        log.info("GET /iam-api/v1/organizations/{}/functions/{}/users — rejected: {}",
            orgIdentifier, functionId, e.getMessage());
        return ResponseEntity.status(403).build();
      }
    }

    // Existence checks (only after authorization)
    try {
      if (!this.keycloakAdminClient.organizationExists(orgIdentifier)) {
        return ResponseEntity.notFound().build();
      }
      if (!this.keycloakAdminClient.isFunctionAttachedToOrg(orgIdentifier, functionId)) {
        return ResponseEntity.notFound().build();
      }

      final List<RightsHolderEntry> entries =
          this.keycloakAdminClient.fetchFunctionRightsHolders(orgIdentifier, functionId);

      final List<UserRightEntryDto> dtos = entries.stream()
          .map(e -> new UserRightEntryDto(
              e.userId(), e.personalIdentityNumber(), e.name(), e.right(), e.scope()))
          .toList();

      log.debug("Returning {} rights holders for {}/{}", dtos.size(), orgIdentifier, functionId);
      return ResponseEntity.ok(new RightsHoldersResponse(dtos));
    }
    catch (final KeycloakAdminException e) {
      log.error("GET /iam-api/v1/organizations/{}/functions/{}/users — Keycloak error: {}",
          orgIdentifier, functionId, e.getMessage());
      return ResponseEntity.status(500).build();
    }
  }

  record RightsHoldersResponse(@NonNull List<UserRightEntryDto> users) {
  }

  record UserRightEntryDto(
      @JsonProperty("user_id") @NonNull String userId,
      @JsonProperty("personal_identity_number") @Nullable String personalIdentityNumber,
      @Nullable String name,
      @NonNull String right,
      @NonNull String scope) {
  }

}
