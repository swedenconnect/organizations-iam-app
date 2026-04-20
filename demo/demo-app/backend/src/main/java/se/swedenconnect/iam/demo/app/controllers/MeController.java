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
package se.swedenconnect.iam.demo.app.controllers;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import se.swedenconnect.iam.demo.app.config.DemoAppProperties;
import se.swedenconnect.iam.security.autoconfigure.IamSecurityProperties;
import se.swedenconnect.iam.security.claims.FunctionScopedAuthority;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId;

/**
 * Endpoint exposing the currently authenticated user's information and organizational rights
 * in the {@code demo} function.
 *
 * <p>For non-superusers, organizations are extracted from the {@code org_rights} ID token claim.
 * For superusers, organizations are fetched server-side from the IAM Service API so the frontend
 * does not need a separate OAuth2 flow.</p>
 *
 * @author Martin Lindström
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class MeController {

  private final RestClient resourceServerRestClient;
  private final String iamApiBaseUrl;
  private final @Nullable String function;

  public MeController(
      @Qualifier("resourceServerRestClient") final @NonNull RestClient resourceServerRestClient,
      final @NonNull DemoAppProperties demoAppProperties,
      final @NonNull IamSecurityProperties iamSecurityProperties) {
    this.resourceServerRestClient = resourceServerRestClient;
    this.iamApiBaseUrl = demoAppProperties.getBaseUrl();
    this.function = iamSecurityProperties.getFunction();
  }

  @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> me(@AuthenticationPrincipal final OidcUser oidcUser) {
    final boolean isSuperuser = oidcUser.getAuthorities().stream()
        .anyMatch(a -> "ROLE_SUPERUSER".equals(a.getAuthority()));

    final List<OrgEntry> organizations;
    if (isSuperuser) {
      try {
        organizations = this.fetchOrganizationsFromIamApi();
      }
      catch (final ClientAuthorizationRequiredException e) {
        log.debug("No access token for iam-api — returning authorization URL");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"authorizationUrl\":\"/oauth2/authorization/iam-api\"}");
      }
    }
    else {
      final Map<String, String> orgNames = resolveOrgNames(oidcUser);
      organizations = oidcUser.getAuthorities().stream()
          .filter(a -> a instanceof FunctionScopedAuthority)
          .map(a -> (FunctionScopedAuthority) a)
          .map(a -> {
            final String orgId = a.getOrgIdentifier().toString();
            final String orgName = orgNames.getOrDefault(orgId, orgId);
            return new OrgEntry(orgId, orgName, a.getRight().getRight());
          })
          .toList();
    }

    return ResponseEntity.ok(new MeResponse(
        oidcUser.getSubject(),
        oidcUser.getFullName(),
        oidcUser.<String>getClaim("https://id.oidc.se/claim/personalIdentityNumber"),
        isSuperuser,
        organizations
    ));
  }

  /**
   * Fetches all organizations from the IAM Service API and filters them by the configured
   * function.
   *
   * @return the filtered list of organizations; never null
   * @throws ClientAuthorizationRequiredException if the {@code iam-api} access token
   *     is not yet available
   */
  private @NonNull List<OrgEntry> fetchOrganizationsFromIamApi() {
    final String json = this.resourceServerRestClient.get()
        .uri(this.iamApiBaseUrl + "/iam-api/v1/organizations")
        .attributes(clientRegistrationId("iam-api"))
        .exchange((req, resp) -> {
          final byte[] body = resp.getBody().readAllBytes();
          if (!resp.getStatusCode().is2xxSuccessful()) {
            log.warn("IAM Service API returned {} for organizations", resp.getStatusCode());
            return null;
          }
          return new String(body, StandardCharsets.UTF_8);
        });

    if (json == null) {
      return List.of();
    }

    try {
      @SuppressWarnings("unchecked")
      final Map<String, Map<String, Object>> orgMap =
          new tools.jackson.databind.ObjectMapper().readValue(json,
              new tools.jackson.core.type.TypeReference<Map<String, Map<String, Object>>>() {});

      final List<OrgEntry> entries = new ArrayList<>();
      for (final var entry : orgMap.entrySet()) {
        final String orgId = entry.getKey();
        final Map<String, Object> attrs = entry.getValue();

        @SuppressWarnings("unchecked")
        final List<String> attachedFunctions =
            attrs.get("attached_functions") instanceof final List<?> list
                ? (List<String>) list : List.of();
        if (!attachedFunctions.isEmpty()
            && this.function != null
            && !attachedFunctions.contains(this.function)) {
          continue;
        }

        final String nameEn = attrs.get("name#en") instanceof final String s ? s : null;
        final String nameSv = attrs.get("name#sv") instanceof final String s ? s : null;
        final String orgName = nameEn != null ? nameEn : (nameSv != null ? nameSv : orgId);

        entries.add(new OrgEntry(orgId, orgName, "admin"));
      }
      return entries;
    }
    catch (final Exception e) {
      log.error("Failed to parse organizations response from IAM Service API", e);
      return List.of();
    }
  }

  private static Map<String, String> resolveOrgNames(final OidcUser oidcUser) {
    final List<?> orgRights = oidcUser.getClaim("org_rights");
    if (orgRights == null) {
      return Map.of();
    }
    final Map<String, String> names = new HashMap<>();
    for (final Object entry : orgRights) {
      if (!(entry instanceof Map<?, ?> map)) {
        continue;
      }
      final Object orgId = map.get("organization_identifier");
      if (!(orgId instanceof String orgIdStr)) {
        continue;
      }
      String name = null;
      if (map.get("organization_name#en") instanceof String en && !en.isBlank()) {
        name = en;
      } else if (map.get("organization_name#sv") instanceof String sv && !sv.isBlank()) {
        name = sv;
      }
      names.put(orgIdStr, name != null ? name : orgIdStr);
    }
    return names;
  }

  record MeResponse(
      String sub,
      String name,
      String personalIdentityNumber,
      boolean superuser,
      List<OrgEntry> organizations
  ) {}

  record OrgEntry(
      String orgId,
      String orgName,
      String right
  ) {}

}
