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
package se.swedenconnect.iam.admin.keycloak;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.keycloak.model.FunctionInfo;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;
import se.swedenconnect.iam.admin.keycloak.model.UserInfo;
import se.swedenconnect.iam.commons.types.LocalizedString;

import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Low-level client for the KeyCloak Admin REST API.
 *
 * <p>Provides typed methods for the API operations needed by the IAM Admin application.
 * Service account token acquisition is delegated to Spring Security's
 * {@link OAuth2AuthorizedClientManager} using the {@code iam-admin-sa} client registration
 * (client_credentials grant, private_key_jwt authentication). Token caching and refresh
 * are handled automatically by the underlying {@code OAuth2AuthorizedClientService}.</p>
 *
 * @author Martin Lindström
 */
@Component
@Slf4j
public class KeycloakAdminClient {

  private static final int PAGE_SIZE = 500;

  private final RestClient restClient;
  private final OAuth2AuthorizedClientManager authorizedClientManager;
  private final String adminApiBase;
  private final boolean pnrUserids;
  private final IamAdminProperties properties;

  public KeycloakAdminClient(
      final @NonNull OAuth2AuthorizedClientManager authorizedClientManager,
      final RestClient.Builder restClientBuilder,
      final @NonNull IamAdminProperties properties) {

    this.authorizedClientManager = authorizedClientManager;
    this.adminApiBase = properties.getAdminApiBase();
    this.pnrUserids = properties.isPnrUserids();
    this.properties = properties;
    this.restClient = restClientBuilder.build();

    log.debug("KeycloakAdminClient initialized — adminApiBase={}", this.adminApiBase);
  }

  // ---------------------------------------------------------------------------
  // Token management
  // ---------------------------------------------------------------------------

  private String getAccessToken() {
    final OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
        .withClientRegistrationId("iam-admin-sa")
        .principal("service-account")
        .build();

    final OAuth2AuthorizedClient authorizedClient =
        this.authorizedClientManager.authorize(authorizeRequest);

    if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
      throw new KeycloakAdminException(
          "Failed to obtain service account access token via OAuth2AuthorizedClientManager");
    }

    log.debug("Service account access token obtained (expires at {})",
        authorizedClient.getAccessToken().getExpiresAt());

    return authorizedClient.getAccessToken().getTokenValue();
  }

  // ---------------------------------------------------------------------------
  // Generic GET helper
  // ---------------------------------------------------------------------------

  private <T> T adminGet(
      final @NonNull URI uri,
      final @NonNull ParameterizedTypeReference<T> type) {

    return this.adminGet(uri, type, true);
  }

  private <T> T adminGet(
      final @NonNull URI uri,
      final @NonNull ParameterizedTypeReference<T> type,
      final boolean retryOn401) {

    final String token = this.getAccessToken();
    try {
      return this.restClient.get()
          .uri(uri)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .retrieve()
          .body(type);
    }
    catch (final RestClientResponseException e) {
      if (retryOn401 && e.getStatusCode().value() == 401) {
        log.warn("Keycloak admin API returned 401 for GET {} — retrying", uri);
        return this.adminGet(uri, type, false);
      }
      log.error("Keycloak admin API error {} for GET {}: {}",
          e.getStatusCode(), uri, e.getResponseBodyAsString());
      throw new KeycloakAdminException(
          "Keycloak admin API returned " + e.getStatusCode() + " for GET " + uri, e);
    }
    catch (final KeycloakAdminException e) {
      throw e;
    }
    catch (final Exception e) {
      throw new KeycloakAdminException("Keycloak admin API call failed for GET " + uri, e);
    }
  }

  private <T> T adminGet(
      final @NonNull String path,
      final @NonNull ParameterizedTypeReference<T> type) {

    return this.adminGet(URI.create(this.adminApiBase + path), type);
  }

  // ---------------------------------------------------------------------------
  // Generic POST helper
  // ---------------------------------------------------------------------------

  /**
   * Posts {@code body} to {@code adminApiBase + path} and returns the {@code Location} header
   * value from the response, or {@code null} if no such header is present.
   *
   * @param path relative path under the admin API base
   * @param body request body (serialised to JSON by Jackson)
   * @return the {@code Location} header value, or {@code null}
   * @throws KeycloakAdminException on any HTTP or connectivity error
   */
  private @Nullable String adminPost(final @NonNull String path, final @NonNull Object body) {
    return this.adminPost(path, body, true);
  }

  private @Nullable String adminPost(final @NonNull String path, final @NonNull Object body, final boolean retryOn401) {
    final String token = this.getAccessToken();
    try {
      final var responseSpec = this.restClient.post()
          .uri(this.adminApiBase + path)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .toBodilessEntity();
      final var location = responseSpec.getHeaders().getLocation();
      return location != null ? location.toString() : null;
    }
    catch (final RestClientResponseException e) {
      if (retryOn401 && e.getStatusCode().value() == 401) {
        log.warn("Keycloak admin API returned 401 for POST {} — retrying", path);
        return this.adminPost(path, body, false);
      }
      log.error("Keycloak admin API error {} for POST {}: {}",
          e.getStatusCode(), path, e.getResponseBodyAsString());
      throw new KeycloakAdminException(
          "Keycloak admin API returned " + e.getStatusCode() + " for POST " + path, e);
    }
    catch (final KeycloakAdminException e) {
      throw e;
    }
    catch (final Exception e) {
      throw new KeycloakAdminException("Keycloak admin API call failed for POST " + path, e);
    }
  }

  /**
   * Posts {@code body} to {@code adminApiBase + path}, reads the JSON response body, and
   * returns the value of the {@code id} field. Use this for Authorization Services endpoints
   * ({@code /authz/resource-server/policy/*}, {@code /authz/resource-server/permission/*})
   * which return the created resource in the body rather than a {@code Location} header.
   *
   * @param path relative path under the admin API base
   * @param body request body (serialised to JSON by Jackson)
   * @return the {@code id} field from the response body
   * @throws KeycloakAdminException if no id is present in the response or on any HTTP error
   */
  private @NonNull String adminPostForId(final @NonNull String path, final @NonNull Object body) {
    return this.adminPostForId(path, body, true);
  }

  private @NonNull String adminPostForId(final @NonNull String path, final @NonNull Object body, final boolean retryOn401) {
    final String token = this.getAccessToken();
    try {
      @SuppressWarnings("unchecked")
      final Map<String, Object> responseBody = (Map<String, Object>) this.restClient.post()
          .uri(this.adminApiBase + path)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(new ParameterizedTypeReference<Map<String, Object>>() {});
      if (responseBody == null) {
        throw new KeycloakAdminException("POST " + path + " returned empty body");
      }
      final String id = responseBody.get("id") instanceof final String s ? s : null;
      if (id == null) {
        throw new KeycloakAdminException(
            "POST " + path + " response body contained no 'id' field");
      }
      return id;
    }
    catch (final RestClientResponseException e) {
      if (retryOn401 && e.getStatusCode().value() == 401) {
        log.warn("Keycloak admin API returned 401 for POST {} — retrying", path);
        return this.adminPostForId(path, body, false);
      }
      log.error("Keycloak admin API error {} for POST {}: {}",
          e.getStatusCode(), path, e.getResponseBodyAsString());
      throw new KeycloakAdminException(
          "Keycloak admin API returned " + e.getStatusCode() + " for POST " + path, e);
    }
    catch (final KeycloakAdminException e) {
      throw e;
    }
    catch (final Exception e) {
      throw new KeycloakAdminException("Keycloak admin API call failed for POST " + path, e);
    }
  }

  /**
   * Issues a PUT request to {@code adminApiBase + path} with no request or response body.
   *
   * @param path relative path under the admin API base
   * @throws KeycloakAdminException on any HTTP or connectivity error
   */
  private void adminPut(final @NonNull String path) {
    this.adminPut(path, true);
  }

  private void adminPut(final @NonNull String path, final boolean retryOn401) {
    final String token = this.getAccessToken();
    try {
      this.restClient.put()
          .uri(this.adminApiBase + path)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .retrieve()
          .toBodilessEntity();
    }
    catch (final RestClientResponseException e) {
      if (retryOn401 && e.getStatusCode().value() == 401) {
        log.warn("Keycloak admin API returned 401 for PUT {} — retrying", path);
        this.adminPut(path, false);
        return;
      }
      log.error("Keycloak admin API error {} for PUT {}: {}",
          e.getStatusCode(), path, e.getResponseBodyAsString());
      throw new KeycloakAdminException(
          "Keycloak admin API returned " + e.getStatusCode() + " for PUT " + path, e);
    }
    catch (final KeycloakAdminException e) {
      throw e;
    }
    catch (final Exception e) {
      throw new KeycloakAdminException("Keycloak admin API call failed for PUT " + path, e);
    }
  }

  private void adminPutWithBody(final @NonNull String path, final @NonNull Object body) {
    this.adminPutWithBody(path, body, true);
  }

  private void adminPutWithBody(
      final @NonNull String path,
      final @NonNull Object body,
      final boolean retryOn401) {
    final String token = this.getAccessToken();
    try {
      this.restClient.put()
          .uri(this.adminApiBase + path)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .toBodilessEntity();
    }
    catch (final RestClientResponseException e) {
      if (retryOn401 && e.getStatusCode().value() == 401) {
        log.warn("Keycloak admin API returned 401 for PUT {} — retrying", path);
        this.adminPutWithBody(path, body, false);
        return;
      }
      log.error("Keycloak admin API error {} for PUT {}: {}",
          e.getStatusCode(), path, e.getResponseBodyAsString());
      throw new KeycloakAdminException(
          "Keycloak admin API returned " + e.getStatusCode() + " for PUT " + path, e);
    }
    catch (final KeycloakAdminException e) {
      throw e;
    }
    catch (final Exception e) {
      throw new KeycloakAdminException("Keycloak admin API call failed for PUT " + path, e);
    }
  }

  /**
   * Issues a DELETE request to {@code adminApiBase + path} with no request or response body.
   *
   * @param path relative path under the admin API base
   * @throws KeycloakAdminException on any HTTP or connectivity error
   */
  private void adminDelete(final @NonNull String path) {
    this.adminDelete(path, true);
  }

  private void adminDelete(final @NonNull String path, final boolean retryOn401) {
    final String token = this.getAccessToken();
    try {
      this.restClient.delete()
          .uri(this.adminApiBase + path)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .retrieve()
          .toBodilessEntity();
    }
    catch (final RestClientResponseException e) {
      if (retryOn401 && e.getStatusCode().value() == 401) {
        log.warn("Keycloak admin API returned 401 for DELETE {} — retrying", path);
        this.adminDelete(path, false);
        return;
      }
      log.error("Keycloak admin API error {} for DELETE {}: {}",
          e.getStatusCode(), path, e.getResponseBodyAsString());
      throw new KeycloakAdminException(
          "Keycloak admin API returned " + e.getStatusCode() + " for DELETE " + path, e);
    }
    catch (final KeycloakAdminException e) {
      throw e;
    }
    catch (final Exception e) {
      throw new KeycloakAdminException("Keycloak admin API call failed for DELETE " + path, e);
    }
  }

  /**
   * Extracts the Keycloak group UUID from a {@code Location} header value by returning the
   * substring after the last {@code /}.
   *
   * @param location the {@code Location} header value
   * @return the group UUID
   */
  private static @NonNull String extractGroupIdFromLocation(final @NonNull String location) {
    final int idx = location.lastIndexOf('/');
    return idx >= 0 ? location.substring(idx + 1) : location;
  }

  // ---------------------------------------------------------------------------
  // Functions
  // ---------------------------------------------------------------------------

  /**
   * Fetches all canonical function definitions from the {@code functions} top-level group.
   *
   * @return list of functions; never {@code null}
   */
  public @NonNull List<FunctionInfo> fetchAllFunctions() {
    final String groupId = this.findTopLevelGroupId("functions");
    final List<Map<String, Object>> children = this.adminGet(
        "/groups/" + groupId + "/children?briefRepresentation=false",
        new ParameterizedTypeReference<>() {});
    if (children == null) {
      return List.of();
    }
    return children.stream()
        .map(g -> {
          final LocalizedString name = new LocalizedString();
          final LocalizedString description = new LocalizedString();
          @SuppressWarnings("unchecked")
          final Map<String, Object> attrs =
              g.getOrDefault("attributes", Map.of()) instanceof final Map<?, ?> m
                  ? (Map<String, Object>) m : Map.of();
          for (final Map.Entry<String, Object> attr : attrs.entrySet()) {
            final String val = getFirstListValue(attr.getValue());
            if (val != null) {
              if (attr.getKey().startsWith("name#")) {
                name.addFromClaim(attr.getKey(), val);
              }
              else if (attr.getKey().startsWith("description")) {
                description.addFromClaim(attr.getKey(), val);
              }
            }
          }
          final LocalizedString descOrNull = description.asMap().isEmpty() ? null : description;
          return new FunctionInfo(getString(g, "name"), name, descOrNull);
        })
        .toList();
  }

  // ---------------------------------------------------------------------------
  // Organizations
  // ---------------------------------------------------------------------------

  /**
   * Fetches all organization groups from the {@code orgs} top-level group, including each
   * org's attached function sub-groups.
   *
   * @return list of organizations; never {@code null}
   */
  public @NonNull List<OrganizationInfo> fetchAllOrganizationGroups() {
    final String orgsGroupId = this.findTopLevelGroupId("orgs");
    final List<Map<String, Object>> orgGroups = this.adminGet(
        "/groups/" + orgsGroupId + "/children?briefRepresentation=false",
        new ParameterizedTypeReference<>() {});
    if (orgGroups == null) {
      return List.of();
    }

    final List<OrganizationInfo> result = new ArrayList<>();
    for (final Map<String, Object> org : orgGroups) {
      final String groupId = getString(org, "id");

      String orgIdentifier = getFirstAttr(org, "organization_identifier");
      if (orgIdentifier == null) {
        orgIdentifier = getString(org, "name");
      }

      // Fetch org's children to discover attached function sub-groups
      final List<Map<String, Object>> children = this.fetchGroupChildren(groupId);
      final List<String> attachedFunctions = children.stream()
          .map(c -> getString(c, "name"))
          .filter(name -> name != null && !name.startsWith("_"))
          .toList();

      final LocalizedString orgName = new LocalizedString();
      @SuppressWarnings("unchecked")
      final Map<String, Object> orgAttrs =
          org.getOrDefault("attributes", Map.of()) instanceof final Map<?, ?> m
              ? (Map<String, Object>) m : Map.of();
      for (final Map.Entry<String, Object> attr : orgAttrs.entrySet()) {
        if (attr.getKey().startsWith("organization_name")) {
          final String val = getFirstListValue(attr.getValue());
          if (val != null) {
            orgName.addFromClaim(attr.getKey(), val);
          }
        }
      }

      final String contactInfoJson = getFirstListValue(orgAttrs.get("contact_info"));
      final Map<String, String> contactInfo = parseContactInfo(contactInfoJson);
      final String contactEmail = contactInfo.get("email");
      final String contactPhone = contactInfo.get("phone_number");

      result.add(new OrganizationInfo(
          orgIdentifier,
          orgName,
          groupId,
          attachedFunctions,
          contactEmail,
          contactPhone));
    }
    return result;
  }

  /**
   * Checks whether an organization with the given identifier already exists under the
   * {@code orgs} top-level group.
   *
   * @param orgIdentifier the organization number (10 digits)
   * @return {@code true} if the organization exists, {@code false} otherwise
   */
  public boolean organizationExists(final @NonNull String orgIdentifier) {
    final String orgsGroupId = this.findTopLevelGroupId("orgs");
    final List<Map<String, Object>> children = this.fetchGroupChildren(orgsGroupId);
    return children.stream().anyMatch(c -> orgIdentifier.equals(getString(c, "name")));
  }

  /**
   * Creates a new organization group under the {@code orgs} top-level group, along with the
   * standard {@code _admin}, {@code _write}, and {@code _read} sub-groups.
   *
   * <p>No rollback is performed if a sub-group creation fails — the caller should treat a
   * {@link KeycloakAdminException} as a partial-failure signal and investigate manually.</p>
   *
   * @param orgIdentifier the organization number (10 digits), used as the group name
   * @param nameSv        Swedish organization name
   * @param nameEn        English organization name
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public void createOrganization(
      final @NonNull String orgIdentifier,
      final @NonNull String nameSv,
      final @NonNull String nameEn) {

    log.debug("Creating organization group '{}' under /orgs", orgIdentifier);

    final String orgsGroupId = this.findTopLevelGroupId("orgs");
    log.debug("Resolved 'orgs' group id: {}", orgsGroupId);

    final Map<String, Object> orgGroupBody = Map.of(
        "name", orgIdentifier,
        "attributes", Map.of(
            "organization_identifier", List.of(orgIdentifier),
            "organization_name#sv", List.of(nameSv),
            "organization_name#en", List.of(nameEn)));

    final String location = this.adminPost("/groups/" + orgsGroupId + "/children", orgGroupBody);
    if (location == null) {
      throw new KeycloakAdminException(
          "Keycloak did not return a Location header after creating org group '" + orgIdentifier + "'");
    }
    final String orgGroupId = extractGroupIdFromLocation(location);
    log.debug("Organization group '{}' created with id: {}", orgIdentifier, orgGroupId);

    for (final String subGroupName : List.of("_admin", "_write", "_read")) {
      this.adminPost("/groups/" + orgGroupId + "/children", Map.of("name", subGroupName));
      log.debug("Created sub-group '{}' under org group '{}'", subGroupName, orgIdentifier);
    }

    log.info("Organization '{}' created in Keycloak with sub-groups _admin, _write, _read",
        orgIdentifier);
  }

  /**
   * Checks whether a function with the given identifier exists under the {@code functions}
   * top-level group.
   *
   * @param functionId the function identifier (group name)
   * @return {@code true} if the function exists, {@code false} otherwise
   */
  public boolean functionExists(final @NonNull String functionId) {
    final String functionsGroupId = this.findTopLevelGroupId("functions");
    final List<Map<String, Object>> children = this.fetchGroupChildren(functionsGroupId);
    return children.stream().anyMatch(c -> functionId.equals(getString(c, "name")));
  }

  /**
   * Creates a new function group under the {@code functions} top-level group.
   *
   * @param functionId    the function identifier (group name), must match {@code [a-z0-9_-]+}
   * @param nameSv        Swedish display name
   * @param nameEn        English display name
   * @param descriptionSv Swedish description (optional)
   * @param descriptionEn English description (optional)
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public void createFunction(
      final @NonNull String functionId,
      final @NonNull String nameSv,
      final @NonNull String nameEn,
      final @Nullable String descriptionSv,
      final @Nullable String descriptionEn) {

    log.debug("Creating function group '{}' under /functions", functionId);

    final String functionsGroupId = this.findTopLevelGroupId("functions");
    log.debug("Resolved 'functions' group id: {}", functionsGroupId);

    final Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("name#sv", List.of(nameSv));
    attributes.put("name#en", List.of(nameEn));
    if (descriptionSv != null) {
      attributes.put("description#sv", List.of(descriptionSv));
    }
    if (descriptionEn != null) {
      attributes.put("description#en", List.of(descriptionEn));
    }

    final Map<String, Object> body = Map.of("name", functionId, "attributes", attributes);

    final String location = this.adminPost("/groups/" + functionsGroupId + "/children", body);
    if (location == null) {
      throw new KeycloakAdminException(
          "Keycloak did not return a Location header after creating function group '" + functionId + "'");
    }

    log.info("Function '{}' created in Keycloak under /functions", functionId);
  }

  /**
   * Updates the display names and descriptions of an existing function group in Keycloak.
   *
   * <p>Fetches the current group representation, merges the new attribute values, and
   * issues a PUT to persist the changes. The function identifier (group name) is immutable.</p>
   *
   * @param functionId    the function identifier (group name)
   * @param nameSv        new Swedish display name
   * @param nameEn        new English display name
   * @param descriptionSv new Swedish description, or {@code null} to clear
   * @param descriptionEn new English description, or {@code null} to clear
   * @throws KeycloakAdminException if the function group is not found or on API error
   */
  public void updateFunction(
      final @NonNull String functionId,
      final @NonNull String nameSv,
      final @NonNull String nameEn,
      final @Nullable String descriptionSv,
      final @Nullable String descriptionEn) {

    final String functionsGroupId = this.findTopLevelGroupId("functions");
    final List<Map<String, Object>> children = this.fetchGroupChildren(functionsGroupId);
    final String funcGroupId = children.stream()
        .filter(c -> functionId.equals(getString(c, "name")))
        .map(c -> getString(c, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new KeycloakAdminException("Function group not found: " + functionId));

    final Map<String, Object> existing = this.adminGet(
        "/groups/" + funcGroupId, new ParameterizedTypeReference<>() {});
    if (existing == null) {
      throw new KeycloakAdminException("Function group not found: " + funcGroupId);
    }

    @SuppressWarnings("unchecked")
    final Map<String, Object> attrs =
        existing.getOrDefault("attributes", Map.of()) instanceof final Map<?, ?> m
            ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();

    attrs.put("name#sv", List.of(nameSv));
    attrs.put("name#en", List.of(nameEn));
    if (descriptionSv != null && !descriptionSv.isBlank()) {
      attrs.put("description#sv", List.of(descriptionSv));
    } else {
      attrs.remove("description#sv");
    }
    if (descriptionEn != null && !descriptionEn.isBlank()) {
      attrs.put("description#en", List.of(descriptionEn));
    } else {
      attrs.remove("description#en");
    }

    final Map<String, Object> body = new LinkedHashMap<>(existing);
    body.put("attributes", attrs);

    this.adminPutWithBody("/groups/" + funcGroupId, body);
    log.info("Function '{}' updated in Keycloak", functionId);
  }

  /**
   * Permanently deletes a function and all its Keycloak artifacts.
   *
   * <p>Performs the following steps:
   * <ol>
   *   <li>Resolves the canonical function group id under {@code /functions}.</li>
   *   <li>Finds all org groups that have this function attached as a sub-group.</li>
   *   <li>For each attached org and each configured authz client:
   *     <ul>
   *       <li>Deletes the three scope permissions {@code permission-&lt;org&gt;:&lt;func&gt;:read/write/admin}.</li>
   *       <li>Deletes the three group policies {@code policy-&lt;org&gt;-&lt;func&gt;-read/write/admin}.</li>
   *       <li>Removes and deletes the three client scopes {@code &lt;org&gt;:&lt;func&gt;:read/write/admin}.</li>
   *     </ul>
   *   </li>
   *   <li>Deletes the org function sub-group for each attached org.</li>
   *   <li>Deletes the canonical function group under {@code /functions}.</li>
   * </ol>
   *
   * <p>Missing optional artifacts (policies, permissions, scopes) are logged at DEBUG and
   * skipped rather than causing a failure.</p>
   *
   * @param functionId the function identifier
   * @throws KeycloakAdminException if the canonical function group is not found or on
   *                                unexpected API errors
   */
  public void deleteFunction(final @NonNull String functionId) {
    // Step 1 — Resolve canonical function group id
    final String functionsGroupId = this.findTopLevelGroupId("functions");
    final List<Map<String, Object>> funcChildren = this.fetchGroupChildren(functionsGroupId);
    final String funcGroupId = funcChildren.stream()
        .filter(c -> functionId.equals(getString(c, "name")))
        .map(c -> getString(c, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new KeycloakAdminException("Function group not found: " + functionId));

    // Step 2 — Find all orgs that have this function attached
    final String orgsGroupId = this.findTopLevelGroupId("orgs");
    final List<Map<String, Object>> orgGroups = this.fetchGroupChildren(orgsGroupId);

    for (final Map<String, Object> orgGroup : orgGroups) {
      final String orgGroupId = getString(orgGroup, "id");
      final String orgIdentifier = getString(orgGroup, "name");
      if (orgGroupId == null || orgIdentifier == null) {
        continue;
      }

      final List<Map<String, Object>> orgChildren = this.fetchGroupChildren(orgGroupId);
      final Map<String, Object> funcSubGroup = orgChildren.stream()
          .filter(c -> functionId.equals(getString(c, "name")))
          .findFirst()
          .orElse(null);

      if (funcSubGroup == null) {
        // Function not attached to this org
        continue;
      }

      // Step 3 — Clean up per-client authz artifacts and client scopes
      final List<String> clientUuids = this.resolveIamAdminManagedClientUuids();

      // Fetch all realm client scopes once per org
      final List<Map<String, Object>> allScopes = this.adminGet(
          "/client-scopes", new ParameterizedTypeReference<>() {});

      for (final String clientUuid : clientUuids) {
        for (final String level : List.of("read", "write", "admin")) {
          final String permName = "permission-" + orgIdentifier + ":" + functionId + ":" + level;
          final String encoded = URLEncoder.encode(permName, StandardCharsets.UTF_8);
          final List<Map<String, Object>> perms = this.adminGet(
              "/clients/" + clientUuid + "/authz/resource-server/permission?name=" + encoded + "&exact=true",
              new ParameterizedTypeReference<>() {});
          if (perms != null && !perms.isEmpty()) {
            final String permId = getString(perms.getFirst(), "id");
            if (permId != null) {
              this.adminDelete("/clients/" + clientUuid + "/authz/resource-server/permission/" + permId);
              log.debug("Deleted permission '{}' on client {}", permName, clientUuid);
            }
          } else {
            log.debug("Permission '{}' not found on client {} — skipping", permName, clientUuid);
          }

          final String policyName = "policy-" + orgIdentifier + "-" + functionId + "-" + level;
          final String encodedPolicy = URLEncoder.encode(policyName, StandardCharsets.UTF_8);
          final List<Map<String, Object>> policies = this.adminGet(
              "/clients/" + clientUuid + "/authz/resource-server/policy?name=" + encodedPolicy + "&exact=true",
              new ParameterizedTypeReference<>() {});
          if (policies != null && !policies.isEmpty()) {
            final String policyId = getString(policies.getFirst(), "id");
            if (policyId != null) {
              this.adminDelete("/clients/" + clientUuid + "/authz/resource-server/policy/" + policyId);
              log.debug("Deleted policy '{}' on client {}", policyName, clientUuid);
            }
          } else {
            log.debug("Policy '{}' not found on client {} — skipping", policyName, clientUuid);
          }

          final String scopeName = orgIdentifier + ":" + functionId + ":" + level;
          final String scopeId = allScopes == null ? null : allScopes.stream()
              .filter(s -> scopeName.equals(getString(s, "name")))
              .map(s -> getString(s, "id"))
              .filter(Objects::nonNull)
              .findFirst()
              .orElse(null);
          if (scopeId != null) {
            try {
              this.adminDelete("/clients/" + clientUuid + "/optional-client-scopes/" + scopeId);
              log.debug("Removed optional client scope '{}' from client {}", scopeName, clientUuid);
            } catch (final KeycloakAdminException e) {
              log.debug("Optional client scope '{}' not on client {} — skipping: {}", scopeName, clientUuid, e.getMessage());
            }
          } else {
            log.debug("Client scope '{}' not found — skipping optional scope removal", scopeName);
          }
        }
      }

      // Delete realm-level client scopes
      for (final String level : List.of("read", "write", "admin")) {
        final String scopeName = orgIdentifier + ":" + functionId + ":" + level;
        final String scopeId = allScopes == null ? null : allScopes.stream()
            .filter(s -> scopeName.equals(getString(s, "name")))
            .map(s -> getString(s, "id"))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
        if (scopeId != null) {
          this.adminDelete("/client-scopes/" + scopeId);
          log.debug("Deleted realm client scope '{}'", scopeName);
        } else {
          log.debug("Realm client scope '{}' not found — skipping", scopeName);
        }
      }

      // Step 3f — Delete the org function sub-group
      final String funcSubGroupId = getString(funcSubGroup, "id");
      if (funcSubGroupId != null) {
        this.adminDelete("/groups/" + funcSubGroupId);
        log.debug("Deleted org function sub-group '{}' under org '{}'", functionId, orgIdentifier);
      }
    }

    // Step 4 — Delete canonical function group
    this.adminDelete("/groups/" + funcGroupId);
    log.info("Function '{}' and all Keycloak artifacts deleted", functionId);
  }

  /**
   * Checks whether the given function is already attached to the given organization.
   *
   * @param orgIdentifier the organization identifier
   * @param functionId    the function identifier
   * @return {@code true} if the function is already a sub-group of the org group
   */
  public boolean isFunctionAttachedToOrg(
      final @NonNull String orgIdentifier,
      final @NonNull String functionId) {

    final String orgGroupId = this.resolveOrgGroupId(orgIdentifier);
    if (orgGroupId == null) {
      return false;
    }
    final List<Map<String, Object>> children = this.fetchGroupChildren(orgGroupId);
    return children.stream().anyMatch(c -> functionId.equals(getString(c, "name")));
  }

  /**
   * Returns {@code true} if the organization has at least one function sub-group attached.
   *
   * @param orgIdentifier the organization identifier
   * @return {@code true} if any function sub-group exists under the org group
   */
  public boolean isFunctionAttachedToOrg_any(final @NonNull String orgIdentifier) {
    final String orgGroupId = this.resolveOrgGroupId(orgIdentifier);
    if (orgGroupId == null) {
      return false;
    }
    final List<Map<String, Object>> children = this.fetchGroupChildren(orgGroupId);
    return children.stream().anyMatch(c -> {
      final String name = getString(c, "name");
      return name != null && !name.startsWith("_");
    });
  }

  /**
   * Attaches a function to an organization in Keycloak.
   *
   * <p>Performs the following steps:
   * <ol>
   *   <li>Creates a function sub-group under the org group (with {@code function_ref} attribute).</li>
   *   <li>Creates {@code _admin}, {@code _write}, and {@code _read} sub-groups under the function group.</li>
   *   <li>Creates three client scopes: {@code <org>:<func>:read}, {@code :write}, and {@code :admin}.</li>
   *   <li>For each configured authz client and each scope level, creates a group policy and a scope permission.</li>
   *   <li>Adds each scope as an optional client scope to both clients.</li>
   * </ol>
   *
   * <p>No rollback is performed on failure — the caller should treat a
   * {@link KeycloakAdminException} as a partial-failure signal.</p>
   *
   * @param orgIdentifier the organization identifier
   * @param functionId    the function identifier
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public void attachFunctionToOrg(
      final @NonNull String orgIdentifier,
      final @NonNull String functionId) {

    // Step 1 — Resolve org group id
    final String orgGroupId = this.resolveOrgGroupId(orgIdentifier);
    if (orgGroupId == null) {
      throw new KeycloakAdminException("Org group not found for: " + orgIdentifier);
    }

    // Resolve client UUIDs
    final List<String> clientUuids = this.resolveIamAdminManagedClientUuids();

    // Step 2 — Create function sub-group under org group
    log.debug("Creating function sub-group '{}' under org group '{}'", functionId, orgIdentifier);
    final String funcGroupLocation = this.adminPost(
        "/groups/" + orgGroupId + "/children",
        Map.of("name", functionId,
            "attributes", Map.of("function_ref", List.of(functionId))));
    if (funcGroupLocation == null) {
      throw new KeycloakAdminException(
          "Keycloak did not return a Location header after creating function sub-group '"
              + functionId + "' under org '" + orgIdentifier + "'");
    }
    final String orgFunctionGroupId = extractGroupIdFromLocation(funcGroupLocation);
    log.debug("Function sub-group '{}' created with id: {}", functionId, orgFunctionGroupId);

    // Step 3 — Create _admin, _write, _read sub-groups under the function group
    for (final String subGroupName : List.of("_admin", "_write", "_read")) {
      this.adminPost("/groups/" + orgFunctionGroupId + "/children", Map.of("name", subGroupName));
      log.debug("Created sub-group '{}' under function group '{}'", subGroupName, functionId);
    }

    // Step 4 — Create 3 client scopes (IDs needed for optional-scope registration in step 6)
    final String readScopeName  = orgIdentifier + ":" + functionId + ":read";
    final String writeScopeName = orgIdentifier + ":" + functionId + ":write";
    final String adminScopeName = orgIdentifier + ":" + functionId + ":admin";
    final String readScopeId  = this.createClientScope(readScopeName);
    final String writeScopeId = this.createClientScope(writeScopeName);
    final String adminScopeId = this.createClientScope(adminScopeName);
    log.debug("Client scopes created — read:{}, write:{}, admin:{}", readScopeId, writeScopeId, adminScopeId);

    // Step 5 — For each client, create authz scopes, group policies and scope permissions.
    // KeyCloak Authorization Services maintains its own scope registry on each resource server,
    // separate from OAuth2 client scopes. Authz scopes must exist before permissions can
    // reference them.
    for (final String clientUuid : clientUuids) {
      this.createAuthzScope(clientUuid, readScopeName);
      this.createAuthzScope(clientUuid, writeScopeName);
      this.createAuthzScope(clientUuid, adminScopeName);
      log.debug("Authorization Services scopes created on client {}", clientUuid);

      this.createPolicyAndPermission(clientUuid, orgIdentifier, functionId,
          "read", readScopeName,
          List.of(
              "/orgs/" + orgIdentifier + "/_read",
              "/orgs/" + orgIdentifier + "/_write",
              "/orgs/" + orgIdentifier + "/_admin",
              "/orgs/" + orgIdentifier + "/" + functionId + "/_read",
              "/orgs/" + orgIdentifier + "/" + functionId + "/_write",
              "/orgs/" + orgIdentifier + "/" + functionId + "/_admin"));
      this.createPolicyAndPermission(clientUuid, orgIdentifier, functionId,
          "write", writeScopeName,
          List.of(
              "/orgs/" + orgIdentifier + "/_write",
              "/orgs/" + orgIdentifier + "/_admin",
              "/orgs/" + orgIdentifier + "/" + functionId + "/_write",
              "/orgs/" + orgIdentifier + "/" + functionId + "/_admin"));
      this.createPolicyAndPermission(clientUuid, orgIdentifier, functionId,
          "admin", adminScopeName,
          List.of(
              "/orgs/" + orgIdentifier + "/_admin",
              "/orgs/" + orgIdentifier + "/" + functionId + "/_admin"));

      // Step 6 — Add each scope as optional to this client
      this.adminPut("/clients/" + clientUuid + "/optional-client-scopes/" + readScopeId);
      this.adminPut("/clients/" + clientUuid + "/optional-client-scopes/" + writeScopeId);
      this.adminPut("/clients/" + clientUuid + "/optional-client-scopes/" + adminScopeId);
      log.debug("Optional scopes registered on client {}", clientUuid);
    }

    log.info("Function '{}' attached to organization '{}' in Keycloak", functionId, orgIdentifier);
  }

  /**
   * Resolves the Keycloak group UUID for the given organization by looking it up as a direct
   * child of the {@code orgs} top-level group.
   *
   * @param orgIdentifier the organization identifier (group name)
   * @return the Keycloak group UUID, or {@code null} if not found
   */
  private @Nullable String resolveOrgGroupId(final @NonNull String orgIdentifier) {
    final String orgsGroupId = this.findTopLevelGroupId("orgs");
    final List<Map<String, Object>> children = this.fetchGroupChildren(orgsGroupId);
    return children.stream()
        .filter(c -> orgIdentifier.equals(getString(c, "name")))
        .map(c -> getString(c, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /**
   * Resolves the internal Keycloak UUID for a client identified by its {@code clientId}.
   *
   * <p>Uses {@link UriComponentsBuilder} to properly encode the clientId query parameter
   * (which may contain {@code ://}).</p>
   *
   * @param clientId the OAuth2 client_id string
   * @return the Keycloak UUID for the client
   * @throws KeycloakAdminException if the client is not found or has no id
   */
  private String resolveClientUuid(final @NonNull String clientId) {
    final URI uri = UriComponentsBuilder.fromUriString(this.adminApiBase + "/clients")
        .queryParam("clientId", clientId)
        .queryParam("exact", "true")
        .build(true)
        .toUri();
    final List<Map<String, Object>> clients = this.adminGet(uri, new ParameterizedTypeReference<>() {});
    if (clients == null || clients.isEmpty()) {
      throw new KeycloakAdminException("Keycloak client not found: " + clientId);
    }
    final String id = getString(clients.getFirst(), "id");
    if (id == null) {
      throw new KeycloakAdminException("Keycloak client has no id: " + clientId);
    }
    return id;
  }

  /**
   * Resolves the full set of managed Keycloak client UUIDs by combining two sources:
   *
   * <ol>
   *   <li><strong>Dynamic discovery</strong> — queries {@code GET /clients?max=500} and
   *       filters clients whose {@code attributes.iam_admin_managed} equals {@code "true"}.</li>
   *   <li><strong>Fallback config</strong> — resolves each client ID in
   *       {@code IamAdminProperties.authzClientIds} (if any) to a UUID via
   *       {@link #resolveClientUuid(String)}.</li>
   * </ol>
   *
   * <p>The result is the union of both sources with duplicates eliminated. If the result
   * is empty a WARN is logged.</p>
   *
   * @return list of Keycloak client UUIDs; never {@code null}
   * @throws KeycloakAdminException on any Keycloak API error
   */
  private @NonNull List<String> resolveIamAdminManagedClientUuids() {
    final LinkedHashSet<String> result = new LinkedHashSet<>();

    // Source 1 — dynamic discovery via iam_admin_managed attribute
    final List<Map<String, Object>> allClients = this.adminGet(
        "/clients?max=500", new ParameterizedTypeReference<>() {});
    if (allClients != null) {
      for (final Map<String, Object> client : allClients) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> attrs =
            client.get("attributes") instanceof final Map<?, ?> m
                ? (Map<String, Object>) m : null;
        if (attrs != null && "true".equals(attrs.get("iam_admin_managed"))) {
          final String uuid = getString(client, "id");
          if (uuid != null) {
            result.add(uuid);
          }
        }
      }
    }
    log.debug("Managed clients discovered via iam_admin_managed attribute: {}", result.size());

    // Source 2 — fallback config
    final List<String> fallbackIds = this.properties.getAuthzClientIds();
    if (fallbackIds != null && !fallbackIds.isEmpty()) {
      log.debug("Resolving {} fallback authz-client-ids", fallbackIds.size());
      for (final String clientId : fallbackIds) {
        result.add(this.resolveClientUuid(clientId));
      }
    }

    if (result.isEmpty()) {
      log.warn("No managed Keycloak clients found (neither via iam_admin_managed attribute"
          + " nor authz-client-ids) — no authz artifacts will be created/deleted");
    }

    return new ArrayList<>(result);
  }

  /**
   * Creates an Authorization Services scope on the given client's resource server.
   *
   * <p>This is distinct from an OAuth2 client scope. KeyCloak's Authorization Services
   * maintains its own scope registry per resource server, and scope permissions must
   * reference scopes from this registry — not global client scope names or UUIDs.</p>
   *
   * @param clientUuid the Keycloak UUID of the client
   * @param scopeName  the scope name (e.g. {@code org:func:read})
   * @throws KeycloakAdminException if creation fails
   */
  private void createAuthzScope(final @NonNull String clientUuid, final @NonNull String scopeName) {
    this.adminPostForId(
        "/clients/" + clientUuid + "/authz/resource-server/scope",
        Map.of("name", scopeName));
    log.debug("Created authz scope '{}' on client {}", scopeName, clientUuid);
  }

  /**
   * Creates a client scope with the given name and returns its Keycloak UUID extracted from
   * the {@code Location} header.
   *
   * @param scopeName the scope name (e.g. {@code org:func:read})
   * @return the Keycloak UUID of the created scope
   * @throws KeycloakAdminException if creation fails or no Location header is returned
   */
  private String createClientScope(final @NonNull String scopeName) {
    final String location = this.adminPost("/client-scopes", Map.of(
        "name", scopeName,
        "protocol", "openid-connect",
        "attributes", Map.of(
            "include.in.token.scope", "true",
            "display.on.consent.screen", "false")));
    if (location == null) {
      throw new KeycloakAdminException(
          "Keycloak did not return a Location header after creating client scope '" + scopeName + "'");
    }
    return extractGroupIdFromLocation(location);
  }

  /**
   * Creates a group policy and a scope permission for a given client, org/func/level combination.
   *
   * <p>Note: The permission endpoint expects scope references by <em>name</em>, not by UUID.
   * The {@code scopeName} parameter is used for the permission body; {@code scopeId} is kept
   * for future reference but is not sent to the permission endpoint.</p>
   *
   * @param clientUuid    the Keycloak UUID of the client
   * @param orgIdentifier the organization identifier
   * @param functionId    the function identifier
   * @param level         the rights level ({@code read}, {@code write}, or {@code admin})
   * @param scopeName     the client scope name (e.g. {@code org:func:read})
   * @param groupPaths    the group paths that grant access at this level
   * @throws KeycloakAdminException on any API error
   */
  private void createPolicyAndPermission(
      final @NonNull String clientUuid,
      final @NonNull String orgIdentifier,
      final @NonNull String functionId,
      final @NonNull String level,
      final @NonNull String scopeName,
      final @NonNull List<String> groupPaths) {

    final String policyName = "policy-" + orgIdentifier + "-" + functionId + "-" + level;
    final List<Map<String, String>> groupEntries = groupPaths.stream()
        .map(p -> Map.of("path", p))
        .toList();
    final String policyId = this.adminPostForId(
        "/clients/" + clientUuid + "/authz/resource-server/policy/group",
        Map.of("name", policyName,
            "groups", groupEntries,
            "logic", "POSITIVE",
            "decisionStrategy", "AFFIRMATIVE"));
    log.debug("Created policy '{}' with id: {}", policyName, policyId);

    final String permissionName = "permission-" + orgIdentifier + "-" + functionId + "-" + level;
    this.adminPostForId(
        "/clients/" + clientUuid + "/authz/resource-server/permission/scope",
        Map.of("name", permissionName,
            "type", "scope",
            "scopes", List.of(scopeName),
            "policies", List.of(policyId),
            "decisionStrategy", "AFFIRMATIVE"));
    log.debug("Created permission '{}' for scope {}", permissionName, scopeName);
  }

  // ---------------------------------------------------------------------------
  // Users
  // ---------------------------------------------------------------------------

  /**
   * Looks up a user by personal identity number and returns their Keycloak UUID if found.
   *
   * <p>This method is used both for duplicate-PIN detection on user creation and for
   * resolving an existing user's ID when a non-superuser admin encounters a conflict.</p>
   *
   * @param pin the personal identity number (12 digits)
   * @return the Keycloak user UUID, or {@link Optional#empty()} if no such user exists
   */
  public Optional<String> findUserIdByPersonalIdentityNumber(final @NonNull String pin) {
    final List<Map<String, Object>> users = this.adminGet(
        "/users?q=personalIdentityNumber:" + pin + "&exact=true",
        new ParameterizedTypeReference<>() {});
    if (users == null || users.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(getString(users.getFirst(), "id"));
  }

  /**
   * Creates a new user in the realm with the given details.
   *
   * <p>The {@code name} is split on the first space into {@code firstName} and {@code lastName}.
   * If there is no space the whole string is used as {@code firstName}. A random UUID is
   * supplied as {@code username} because Keycloak 26 requires it.</p>
   *
   * @param name                   display name (split into first / last name)
   * @param email                  optional email address
   * @param personalIdentityNumber 12-digit personal identity number
   * @param phoneNumber            optional phone number
   * @return the Keycloak UUID of the newly created user
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public @NonNull String createUser(
      final @NonNull String name,
      final @Nullable String email,
      final @NonNull String personalIdentityNumber,
      final @Nullable String phoneNumber) {

    final int spaceIdx = name.indexOf(' ');
    final String firstName = spaceIdx > 0 ? name.substring(0, spaceIdx) : name;
    final String lastName  = spaceIdx > 0 ? name.substring(spaceIdx + 1) : "";

    final Map<String, List<String>> attributes = new LinkedHashMap<>();
    attributes.put("personalIdentityNumber", List.of(personalIdentityNumber));
    if (phoneNumber != null && !phoneNumber.isBlank()) {
      attributes.put("phoneNumber", List.of(phoneNumber));
    }

    final Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", this.pnrUserids ? personalIdentityNumber : UUID.randomUUID().toString());
    body.put("enabled", true);
    body.put("firstName", firstName);
    if (!lastName.isBlank()) {
      body.put("lastName", lastName);
    }
    if (email != null && !email.isBlank()) {
      body.put("email", email);
    }
    body.put("attributes", attributes);

    final String location = this.adminPost("/users", body);
    if (location == null) {
      throw new KeycloakAdminException("Keycloak did not return a Location header after user creation");
    }
    final String path = URI.create(location).getPath();
    final String userId = path.substring(path.lastIndexOf('/') + 1);
    log.debug("User '{}' created in Keycloak with id: {}", name, userId);
    return userId;
  }

  /**
   * Adds a user to the right group for the given organization (org-level right).
   *
   * <p>Group path: {@code /orgs/<orgIdentifier>/_<right>}</p>
   *
   * @param orgIdentifier the organization identifier
   * @param userId        the Keycloak user UUID
   * @param right         the right level ({@code read}, {@code write}, or {@code admin})
   * @throws KeycloakAdminException if the org group or right group is not found, or on API error
   */
  public void addUserToOrgRight(
      final @NonNull String orgIdentifier,
      final @NonNull String userId,
      final @NonNull String right) {

    final String orgGroupId = this.resolveOrgGroupId(orgIdentifier);
    if (orgGroupId == null) {
      throw new KeycloakAdminException("Org group not found: " + orgIdentifier);
    }
    final List<Map<String, Object>> children = this.fetchGroupChildren(orgGroupId);
    final String rightGroupId = children.stream()
        .filter(c -> ("_" + right).equals(getString(c, "name")))
        .map(c -> getString(c, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new KeycloakAdminException(
            "Right group '_" + right + "' not found under org '" + orgIdentifier + "'"));

    this.adminPut("/users/" + userId + "/groups/" + rightGroupId);
    log.info("User '{}' added to org '{}' with right '{}'", userId, orgIdentifier, right);

    // Remove user from the other right groups to avoid duplicate membership
    for (final Map<String, Object> child : children) {
      final String childName = getString(child, "name");
      final String childId = getString(child, "id");
      if (childId == null || childId.equals(rightGroupId)) {
        continue;
      }
      if ("_admin".equals(childName) || "_write".equals(childName) || "_read".equals(childName)) {
        try {
          this.adminDelete("/users/" + userId + "/groups/" + childId);
          log.debug("User '{}' removed from right group '{}' under org '{}'",
              userId, childName, orgIdentifier);
        } catch (final KeycloakAdminException e) {
          log.debug("User '{}' was not in right group '{}' under org '{}' — skipping removal",
              userId, childName, orgIdentifier);
        }
      }
    }
  }

  /**
   * Adds a user to the right group for the given function within an organization.
   *
   * <p>Group path: {@code /orgs/<orgIdentifier>/<functionId>/_<right>}</p>
   *
   * @param orgIdentifier the organization identifier
   * @param functionId    the function identifier
   * @param userId        the Keycloak user UUID
   * @param right         the right level ({@code read}, {@code write}, or {@code admin})
   * @throws KeycloakAdminException if any group in the path is not found, or on API error
   */
  public void addUserToFunctionRight(
      final @NonNull String orgIdentifier,
      final @NonNull String functionId,
      final @NonNull String userId,
      final @NonNull String right) {

    final String orgGroupId = this.resolveOrgGroupId(orgIdentifier);
    if (orgGroupId == null) {
      throw new KeycloakAdminException("Org group not found: " + orgIdentifier);
    }

    final List<Map<String, Object>> orgChildren = this.fetchGroupChildren(orgGroupId);
    final String funcGroupId = orgChildren.stream()
        .filter(c -> functionId.equals(getString(c, "name")))
        .map(c -> getString(c, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new KeycloakAdminException(
            "Function group '" + functionId + "' not found under org '" + orgIdentifier + "'"));

    final List<Map<String, Object>> funcChildren = this.fetchGroupChildren(funcGroupId);
    final String rightGroupId = funcChildren.stream()
        .filter(c -> ("_" + right).equals(getString(c, "name")))
        .map(c -> getString(c, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new KeycloakAdminException(
            "Right group '_" + right + "' not found under function '" + functionId + "'"));

    this.adminPut("/users/" + userId + "/groups/" + rightGroupId);
    log.info("User '{}' added to function '{}' of org '{}' with right '{}'",
        userId, functionId, orgIdentifier, right);

    // Remove user from the other right groups to avoid duplicate membership
    for (final Map<String, Object> child : funcChildren) {
      final String childName = getString(child, "name");
      final String childId = getString(child, "id");
      if (childId == null || childId.equals(rightGroupId)) {
        continue;
      }
      if ("_admin".equals(childName) || "_write".equals(childName) || "_read".equals(childName)) {
        try {
          this.adminDelete("/users/" + userId + "/groups/" + childId);
          log.debug("User '{}' removed from right group '{}' under function '{}' of org '{}'",
              userId, childName, functionId, orgIdentifier);
        } catch (final KeycloakAdminException e) {
          log.debug("User '{}' was not in right group '{}' under function '{}' of org '{}' — skipping removal",
              userId, childName, functionId, orgIdentifier);
        }
      }
    }
  }

  /**
   * Removes a user from the right group for the given organization (org-level right).
   *
   * <p>Group path: {@code /orgs/<orgIdentifier>/_<right>}</p>
   *
   * @param orgIdentifier the organization identifier
   * @param userId        the Keycloak user UUID
   * @param right         the right level ({@code read}, {@code write}, or {@code admin})
   * @throws KeycloakAdminException if the org group or right group is not found, or on API error
   */
  public void removeUserFromOrgRight(
      final @NonNull String orgIdentifier,
      final @NonNull String userId,
      final @NonNull String right) {

    final String orgGroupId = this.resolveOrgGroupId(orgIdentifier);
    if (orgGroupId == null) {
      throw new KeycloakAdminException("Org group not found: " + orgIdentifier);
    }
    final List<Map<String, Object>> children = this.fetchGroupChildren(orgGroupId);
    final String rightGroupId = children.stream()
        .filter(c -> ("_" + right).equals(getString(c, "name")))
        .map(c -> getString(c, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new KeycloakAdminException(
            "Right group '_" + right + "' not found under org '" + orgIdentifier + "'"));

    this.adminDelete("/users/" + userId + "/groups/" + rightGroupId);
    log.info("User '{}' removed from org '{}' right '{}'", userId, orgIdentifier, right);
  }

  /**
   * Removes a user from the right group for the given function within an organization.
   *
   * <p>Group path: {@code /orgs/<orgIdentifier>/<functionId>/_<right>}</p>
   *
   * @param orgIdentifier the organization identifier
   * @param functionId    the function identifier
   * @param userId        the Keycloak user UUID
   * @param right         the right level ({@code read}, {@code write}, or {@code admin})
   * @throws KeycloakAdminException if any group in the path is not found, or on API error
   */
  public void removeUserFromFunctionRight(
      final @NonNull String orgIdentifier,
      final @NonNull String functionId,
      final @NonNull String userId,
      final @NonNull String right) {

    final String orgGroupId = this.resolveOrgGroupId(orgIdentifier);
    if (orgGroupId == null) {
      throw new KeycloakAdminException("Org group not found: " + orgIdentifier);
    }

    final List<Map<String, Object>> orgChildren = this.fetchGroupChildren(orgGroupId);
    final String funcGroupId = orgChildren.stream()
        .filter(c -> functionId.equals(getString(c, "name")))
        .map(c -> getString(c, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new KeycloakAdminException(
            "Function group '" + functionId + "' not found under org '" + orgIdentifier + "'"));

    final List<Map<String, Object>> funcChildren = this.fetchGroupChildren(funcGroupId);
    final String rightGroupId = funcChildren.stream()
        .filter(c -> ("_" + right).equals(getString(c, "name")))
        .map(c -> getString(c, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new KeycloakAdminException(
            "Right group '_" + right + "' not found under function '" + functionId + "'"));

    this.adminDelete("/users/" + userId + "/groups/" + rightGroupId);
    log.info("User '{}' removed from function '{}' of org '{}' right '{}'",
        userId, functionId, orgIdentifier, right);
  }

  /**
   * Updates a user's profile fields in Keycloak.
   *
   * <p>Splits {@code name} on the first space into {@code firstName} and {@code lastName}.
   * Sets {@code email} at the top level. Stores {@code phoneNumber} in the
   * {@code phoneNumber} custom attribute (replaces previous value, or removes the attribute
   * if {@code null} or blank).</p>
   *
   * @param userId      the Keycloak user UUID
   * @param name        the user's full name
   * @param email       optional email address
   * @param phoneNumber optional phone number
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public void updateUser(
      final @NonNull String userId,
      final @NonNull String name,
      final @Nullable String email,
      final @Nullable String phoneNumber) {

    final int spaceIdx = name.indexOf(' ');
    final String firstName = spaceIdx > 0 ? name.substring(0, spaceIdx) : name;
    final String lastName  = spaceIdx > 0 ? name.substring(spaceIdx + 1) : "";

    // Fetch the current user representation to preserve existing attributes
    final Map<String, Object> existing = this.adminGet(
        "/users/" + userId, new ParameterizedTypeReference<>() {});
    if (existing == null) {
      throw new KeycloakAdminException("User not found: " + userId);
    }
    @SuppressWarnings("unchecked")
    final Map<String, Object> existingAttrs =
        existing.getOrDefault("attributes", Map.of()) instanceof final Map<?, ?> m
            ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();

    // Preserve personalIdentityNumber; update phoneNumber
    if (phoneNumber != null && !phoneNumber.isBlank()) {
      existingAttrs.put("phoneNumber", List.of(phoneNumber));
    } else {
      existingAttrs.remove("phoneNumber");
    }

    final Map<String, Object> body = new LinkedHashMap<>();
    body.put("firstName", firstName);
    body.put("lastName", lastName);
    body.put("email", (email != null && !email.isBlank()) ? email : "");
    body.put("attributes", existingAttrs);

    this.adminPutWithBody("/users/" + userId, body);
    log.info("User '{}' profile updated in Keycloak (name='{}', email='{}')", userId, name, email);
  }

  /**
   * Permanently deletes a user from Keycloak.
   *
   * @param userId the Keycloak user UUID
   * @throws KeycloakAdminException if the user is not found or on API error
   */
  public void deleteUser(final @NonNull String userId) {
    this.adminDelete("/users/" + userId);
    log.info("User '{}' permanently deleted from Keycloak", userId);
  }

  // ---------------------------------------------------------------------------
  // Organization update
  // ---------------------------------------------------------------------------

  /**
   * Updates the mutable attributes of an organization group in Keycloak.
   *
   * <p>Only non-null parameters are applied. {@code null} for {@code nameSv}/{@code nameEn}
   * means "do not change". An empty string for {@code contactEmail} or {@code contactPhone}
   * means "clear the attribute".</p>
   *
   * @param orgIdentifier the organization identifier
   * @param nameSv        new Swedish name, or {@code null} to leave unchanged
   * @param nameEn        new English name, or {@code null} to leave unchanged
   * @param contactEmail  new contact email, or {@code null} to leave unchanged, or {@code ""}
   *                      to clear
   * @param contactPhone  new contact phone, or {@code null} to leave unchanged, or {@code ""}
   *                      to clear
   * @throws KeycloakAdminException if the org group is not found or on API error
   */
  public void updateOrganization(
      final @NonNull String orgIdentifier,
      final @Nullable String nameSv,
      final @Nullable String nameEn,
      final @Nullable String contactEmail,
      final @Nullable String contactPhone) {

    final String orgGroupId = this.resolveOrgGroupId(orgIdentifier);
    if (orgGroupId == null) {
      throw new KeycloakAdminException("Org group not found: " + orgIdentifier);
    }

    final Map<String, Object> existing = this.adminGet(
        "/groups/" + orgGroupId, new ParameterizedTypeReference<>() {});
    if (existing == null) {
      throw new KeycloakAdminException("Org group not found: " + orgGroupId);
    }

    @SuppressWarnings("unchecked")
    final Map<String, Object> attrs =
        existing.getOrDefault("attributes", Map.of()) instanceof final Map<?, ?> m
            ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();

    if (nameSv != null) {
      attrs.put("organization_name#sv", List.of(nameSv));
    }
    if (nameEn != null) {
      attrs.put("organization_name#en", List.of(nameEn));
    }
    if (contactEmail != null) {
      if (contactEmail.isBlank()) {
        final String existingJson = getFirstListValue(attrs.get("contact_info"));
        final Map<String, String> contactInfo = parseContactInfo(existingJson);
        contactInfo.remove("email");
        if (contactInfo.isEmpty()) {
          attrs.remove("contact_info");
        } else {
          attrs.put("contact_info", List.of(toContactInfoJson(contactInfo)));
        }
      } else {
        final String existingJson = getFirstListValue(attrs.get("contact_info"));
        final Map<String, String> contactInfo = parseContactInfo(existingJson);
        contactInfo.put("email", contactEmail);
        attrs.put("contact_info", List.of(toContactInfoJson(contactInfo)));
      }
    }
    if (contactPhone != null) {
      if (contactPhone.isBlank()) {
        final String existingJson = getFirstListValue(attrs.get("contact_info"));
        final Map<String, String> contactInfo = parseContactInfo(existingJson);
        contactInfo.remove("phone_number");
        if (contactInfo.isEmpty()) {
          attrs.remove("contact_info");
        } else {
          attrs.put("contact_info", List.of(toContactInfoJson(contactInfo)));
        }
      } else {
        final String existingJson = getFirstListValue(attrs.get("contact_info"));
        final Map<String, String> contactInfo = parseContactInfo(existingJson);
        contactInfo.put("phone_number", contactPhone);
        attrs.put("contact_info", List.of(toContactInfoJson(contactInfo)));
      }
    }

    final Map<String, Object> body = new LinkedHashMap<>(existing);
    body.put("attributes", attrs);

    this.adminPutWithBody("/groups/" + orgGroupId, body);
    log.info("Organization '{}' attributes updated in Keycloak", orgIdentifier);
  }

  /**
   * Permanently deletes the organization group and all its children from Keycloak.
   *
   * <p>This removes the top-level org group under {@code /orgs/<orgIdentifier>} including
   * all sub-groups (_admin, _write, _read, and any attached function sub-groups).</p>
   *
   * <p>The caller is responsible for ensuring that no functions are attached before calling
   * this method, and for cleaning up any client scopes / authz policies that were created
   * when functions were attached. For now this method performs a simple group DELETE.</p>
   *
   * @param orgIdentifier the organization identifier
   * @throws KeycloakAdminException if the org group is not found or on API error
   */
  public void deleteOrganization(final @NonNull String orgIdentifier) {
    final String orgGroupId = this.resolveOrgGroupId(orgIdentifier);
    if (orgGroupId == null) {
      throw new KeycloakAdminException("Org group not found: " + orgIdentifier);
    }
    this.adminDelete("/groups/" + orgGroupId);
    log.info("Organization '{}' permanently deleted from Keycloak", orgIdentifier);
  }

  /**
   * Detaches a function from an organization by removing the function sub-group and all
   * associated Keycloak artifacts created during attachment.
   *
   * <p>Performs the following steps:
   * <ol>
   *   <li>Resolves the function sub-group id under {@code /orgs/<orgIdentifier>}.</li>
   *   <li>Fetches all realm client scopes once.</li>
   *   <li>For each configured authz client and each rights level ({@code read}, {@code write},
   *       {@code admin}): removes the optional-client-scope assignment
   *       ({@code DELETE /clients/{uuid}/optional-client-scopes/{scopeId}}).</li>
   *   <li>Deletes the three realm-level client scopes
   *       ({@code DELETE /client-scopes/{scopeId}}).</li>
   *   <li>For each configured authz client and each rights level: deletes the scope
   *       permission ({@code permission-{org}:{func}:{level}}) and the group policy
   *       ({@code policy-{org}-{func}-{level}}) from Authorization Services.</li>
   *   <li>Deletes the function sub-group under the org group (and its {@code _admin},
   *       {@code _write}, {@code _read} children). The canonical function group under
   *       {@code /functions} is left intact.</li>
   * </ol>
   *
   * <p>If a client scope is not found during cleanup, a WARN is logged and the step is
   * skipped — making the operation idempotent. Unexpected API errors propagate as
   * {@link KeycloakAdminException}.</p>
   *
   * @param orgIdentifier the organization identifier
   * @param functionId    the function identifier
   * @throws KeycloakAdminException if the org or function group is not found, or on API error
   */
  public void detachFunctionFromOrg(
      final @NonNull String orgIdentifier,
      final @NonNull String functionId) {

    final String orgGroupId = this.resolveOrgGroupId(orgIdentifier);
    if (orgGroupId == null) {
      throw new KeycloakAdminException("Org group not found: " + orgIdentifier);
    }

    final List<Map<String, Object>> orgChildren = this.fetchGroupChildren(orgGroupId);
    final String funcGroupId = orgChildren.stream()
        .filter(c -> functionId.equals(getString(c, "name")))
        .map(c -> getString(c, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new KeycloakAdminException(
            "Function group '" + functionId + "' not found under org '" + orgIdentifier + "'"));

    // Resolve client UUIDs and fetch all realm client scopes for scope cleanup
    final List<String> clientUuids = this.resolveIamAdminManagedClientUuids();
    final List<Map<String, Object>> allScopes = this.adminGet(
        "/client-scopes", new ParameterizedTypeReference<>() {});

    for (final String level : List.of("read", "write", "admin")) {
      final String scopeName = orgIdentifier + ":" + functionId + ":" + level;
      final String scopeId = allScopes == null ? null : allScopes.stream()
          .filter(s -> scopeName.equals(getString(s, "name")))
          .map(s -> getString(s, "id"))
          .filter(Objects::nonNull)
          .findFirst()
          .orElse(null);

      if (scopeId == null) {
        log.warn("Client scope '{}' not found during detach cleanup — skipping", scopeName);
        continue;
      }

      // Remove optional-client-scope assignment from each client before deleting the scope
      for (final String clientUuid : clientUuids) {
        try {
          this.adminDelete("/clients/" + clientUuid + "/optional-client-scopes/" + scopeId);
          log.debug("Removed optional client scope '{}' from client {}", scopeName, clientUuid);
        } catch (final KeycloakAdminException e) {
          log.debug("Optional client scope '{}' not assigned to client {} — skipping: {}",
              scopeName, clientUuid, e.getMessage());
        }
      }

      // Delete the realm-level client scope
      this.adminDelete("/client-scopes/" + scopeId);
      log.debug("Deleted realm client scope '{}'", scopeName);
    }

    // Delete Authorization Services permissions and policies per client and level
    for (final String clientUuid : clientUuids) {
      for (final String level : List.of("read", "write", "admin")) {
        final String permName = "permission-" + orgIdentifier + ":" + functionId + ":" + level;
        final String encoded = URLEncoder.encode(permName, StandardCharsets.UTF_8);
        final List<Map<String, Object>> perms = this.adminGet(
            "/clients/" + clientUuid + "/authz/resource-server/permission?name=" + encoded + "&exact=true",
            new ParameterizedTypeReference<>() {});
        if (perms != null && !perms.isEmpty()) {
          final String permId = getString(perms.getFirst(), "id");
          if (permId != null) {
            this.adminDelete("/clients/" + clientUuid + "/authz/resource-server/permission/" + permId);
            log.debug("Deleted permission '{}' on client {}", permName, clientUuid);
          }
        } else {
          log.debug("Permission '{}' not found on client {} — skipping", permName, clientUuid);
        }

        final String policyName = "policy-" + orgIdentifier + "-" + functionId + "-" + level;
        final String encodedPolicy = URLEncoder.encode(policyName, StandardCharsets.UTF_8);
        final List<Map<String, Object>> policies = this.adminGet(
            "/clients/" + clientUuid + "/authz/resource-server/policy?name=" + encodedPolicy + "&exact=true",
            new ParameterizedTypeReference<>() {});
        if (policies != null && !policies.isEmpty()) {
          final String policyId = getString(policies.getFirst(), "id");
          if (policyId != null) {
            this.adminDelete("/clients/" + clientUuid + "/authz/resource-server/policy/" + policyId);
            log.debug("Deleted policy '{}' on client {}", policyName, clientUuid);
          }
        } else {
          log.debug("Policy '{}' not found on client {} — skipping", policyName, clientUuid);
        }
      }
    }

    this.adminDelete("/groups/" + funcGroupId);
    log.info("Function '{}' detached from organization '{}' in Keycloak", functionId, orgIdentifier);
  }

  /**
   * Fetches all users in the realm using pagination, flagging superusers.
   *
   * @return list of all users; never {@code null}
   */
  public @NonNull List<UserInfo> fetchAllUsers() {
    final Set<String> superuserIds = this.fetchSuperuserIds();
    final List<Map<String, Object>> allUsers = new ArrayList<>();
    int first = 0;
    while (true) {
      final List<Map<String, Object>> page = this.adminGet(
          "/users?first=" + first + "&max=" + PAGE_SIZE,
          new ParameterizedTypeReference<>() {});
      if (page == null || page.isEmpty()) {
        break;
      }
      allUsers.addAll(page);
      if (page.size() < PAGE_SIZE) {
        break;
      }
      first += PAGE_SIZE;
    }
    return allUsers.stream().map(u -> this.mapUser(u, superuserIds)).toList();
  }

  /**
   * Fetches members of the given KeyCloak group.
   *
   * @param groupId KeyCloak group UUID
   * @return list of group members; never {@code null}
   */
  public @NonNull List<UserInfo> fetchGroupMembers(final @NonNull String groupId) {
    final List<Map<String, Object>> members = this.adminGet(
        "/groups/" + groupId + "/members",
        new ParameterizedTypeReference<>() {});
    return members == null ? List.of()
        : members.stream().map(u -> this.mapUser(u, Set.of())).toList();
  }

  // ---------------------------------------------------------------------------
  // Group hierarchy
  // ---------------------------------------------------------------------------

  /**
   * Searches for a top-level group by exact name and returns its KeyCloak group UUID.
   *
   * @param name exact group name (e.g. {@code "functions"} or {@code "orgs"})
   * @return KeyCloak group UUID
   * @throws KeycloakAdminException if the group cannot be found
   */
  public @NonNull String findTopLevelGroupId(final @NonNull String name) {
    final List<Map<String, Object>> groups = this.adminGet(
        "/groups?search=" + name + "&exact=true",
        new ParameterizedTypeReference<>() {});
    if (groups == null || groups.isEmpty()) {
      throw new KeycloakAdminException("Top-level group not found: " + name);
    }
    return groups.stream()
        .filter(g -> name.equals(g.get("name")))
        .map(g -> getString(g, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new KeycloakAdminException(
            "Group '" + name + "' not found among search results"));
  }

  /**
   * Fetches the immediate children of a KeyCloak group.
   *
   * @param groupId KeyCloak group UUID; if {@code null} returns an empty list
   * @return list of child group representations; never {@code null}
   */
  public @NonNull List<Map<String, Object>> fetchGroupChildren(final @Nullable String groupId) {
    if (groupId == null) {
      return List.of();
    }
    final List<Map<String, Object>> children = this.adminGet(
        "/groups/" + groupId + "/children",
        new ParameterizedTypeReference<>() {});
    return children != null ? children : List.of();
  }

  // ---------------------------------------------------------------------------
  // Superuser role
  // ---------------------------------------------------------------------------

  /**
   * Returns the set of user IDs that hold the {@code superuser} realm role.
   *
   * <p>Returns an empty set if the role does not exist (treated as a non-fatal condition).</p>
   *
   * @return set of KeyCloak user UUIDs; never {@code null}
   */
  public @NonNull Set<String> fetchSuperuserIds() {
    try {
      final List<Map<String, Object>> users = this.adminGet(
          "/roles/superuser/users",
          new ParameterizedTypeReference<>() {});
      if (users == null) {
        return Set.of();
      }
      final Set<String> ids = new HashSet<>();
      for (final Map<String, Object> u : users) {
        final String id = getString(u, "id");
        if (id != null) {
          ids.add(id);
        }
      }
      return Set.copyOf(ids);
    }
    catch (final KeycloakAdminException e) {
      log.debug("Could not fetch superuser role members (role may not exist): {}", e.getMessage());
      return Set.of();
    }
  }

  // ---------------------------------------------------------------------------
  // Single-user lookup
  // ---------------------------------------------------------------------------

  /**
   * Fetches a single KeyCloak user by their UUID.
   *
   * @param userId KeyCloak user UUID
   * @return an Optional containing the UserInfo, or empty if not found
   */
  public @NonNull Optional<UserInfo> fetchUserById(final @NonNull String userId) {
    try {
      final Map<String, Object> raw = this.adminGet(
          "/users/" + userId,
          new ParameterizedTypeReference<>() {});
      if (raw == null) {
        return Optional.empty();
      }
      return Optional.of(mapUser(raw, Set.of()));
    }
    catch (final KeycloakAdminException e) {
      log.debug("fetchUserById({}) — not found or error: {}", userId, e.getMessage());
      return Optional.empty();
    }
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private UserInfo mapUser(
      final @NonNull Map<String, Object> raw,
      final @NonNull Set<String> superuserIds) {
    final String id = getString(raw, "id");
    return new UserInfo(
        id,
        getString(raw, "username"),
        getString(raw, "firstName"),
        getString(raw, "lastName"),
        getString(raw, "email"),
        getFirstAttr(raw, "personalIdentityNumber"),
        getFirstAttr(raw, "phoneNumber"),
        id != null && superuserIds.contains(id),
        List.of());
  }

  private static @Nullable String getString(
      final @NonNull Map<String, Object> map,
      final @NonNull String key) {
    return map.get(key) instanceof final String s ? s : null;
  }

  @SuppressWarnings("unchecked")
  private static @Nullable String getFirstAttr(
      final @NonNull Map<String, Object> map,
      final @NonNull String key) {
    if (!(map.get("attributes") instanceof final Map<?, ?> attrs)) {
      return null;
    }
    return getFirstListValue(attrs.get(key));
  }

  private static @Nullable String getFirstListValue(final @Nullable Object value) {
    if (value instanceof final List<?> list && !list.isEmpty()) {
      return list.getFirst() instanceof final String s ? s : null;
    }
    return null;
  }

  /**
   * Parses the {@code contact_info} group attribute value (a JSON string) into a mutable map.
   * Returns an empty map if the value is null, blank, or not valid JSON.
   */
  private static @NonNull Map<String, String> parseContactInfo(final @Nullable String json) {
    if (json == null || json.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      final Map<String, String> result = new LinkedHashMap<>();
      final String trimmed = json.trim().replaceAll("^\\{|\\}$", "");
      for (final String pair : trimmed.split(",")) {
        final String[] kv = pair.split(":", 2);
        if (kv.length == 2) {
          final String key = kv[0].trim().replaceAll("^\"|\"$", "");
          final String val = kv[1].trim().replaceAll("^\"|\"$", "");
          result.put(key, val);
        }
      }
      return result;
    } catch (final Exception e) {
      log.debug("Failed to parse contact_info attribute '{}': {}", json, e.getMessage());
      return new LinkedHashMap<>();
    }
  }

  /**
   * Serialises a contact info map to a compact JSON string.
   */
  private static @NonNull String toContactInfoJson(final @NonNull Map<String, String> contactInfo) {
    final StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (final Map.Entry<String, String> entry : contactInfo.entrySet()) {
      if (!first) {
        sb.append(",");
      }
      sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
      first = false;
    }
    sb.append("}");
    return sb.toString();
  }

}
