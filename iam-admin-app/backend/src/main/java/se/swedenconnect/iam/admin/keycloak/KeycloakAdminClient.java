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
import org.springframework.web.util.UriComponentsBuilder;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.keycloak.model.ClientArtifactState;
import se.swedenconnect.iam.admin.keycloak.model.FunctionInfo;
import se.swedenconnect.iam.admin.keycloak.model.ManagedClientInfo;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;
import se.swedenconnect.iam.admin.keycloak.model.RightsHolderEntry;
import se.swedenconnect.iam.admin.keycloak.model.UserInfo;
import se.swedenconnect.iam.admin.keycloak.model.UserRight;
import se.swedenconnect.iam.commons.types.LocalizedString;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Low-level client for the KeyCloak Admin REST API.
 *
 * <p>Provides typed methods for the API operations needed by the IAM Admin application.
 * Service account token acquisition is delegated to Spring Security's {@link OAuth2AuthorizedClientManager} using the
 * {@code iam-admin-sa} client registration (client_credentials grant, private_key_jwt authentication). Token caching
 * and refresh are handled automatically by the underlying {@code OAuth2AuthorizedClientService}.</p>
 *
 * @author Martin Lindström
 * @author Felix Hellman
 */
@Component
@Slf4j
public class KeycloakAdminClient {

  private static final int PAGE_SIZE = 500;

  /** The name of the protocol mapper emitting the {@code org_rights} claim. */
  private static final String ORG_RIGHTS_MAPPER = "org-rights-mapper";

  /**
   * Records whether a service account user is wanted. Keycloak turns {@code serviceAccountsEnabled}
   * back on by itself when Authorization Services are enabled, so the flag on the client cannot be
   * read as the answer — the attribute carries the intent instead.
   */
  private static final String SERVICE_ACCOUNT_ATTRIBUTE = "iam_admin_service_account";

  private final RestClient restClient;
  private final OAuth2AuthorizedClientManager authorizedClientManager;
  private final String adminApiBase;
  private final boolean pnrUserids;
  private final IamAdminProperties properties;
  private CacheToken cacheToken;

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
    if (this.cacheToken != null && !this.cacheToken.expired()) {
      return cacheToken.tokenValue;
    }
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
    cacheToken = new CacheToken(authorizedClient.getAccessToken().getExpiresAt().toEpochMilli(),
        authorizedClient.getAccessToken().getTokenValue());
    return cacheToken.tokenValue;
  }

  private class CacheToken {
    public final long expiryTime;
    public final String tokenValue;

    public CacheToken(final long expiryTime, final String tokenValue) {
      this.expiryTime = expiryTime;
      this.tokenValue = tokenValue;
    }

    public boolean expired() {
      return System.currentTimeMillis() >
          Math.min(this.expiryTime, this.expiryTime - TimeUnit.HOURS.toMillis(1));
    }
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
   * Posts {@code body} to {@code adminApiBase + path} and returns the {@code Location} header value from the response,
   * or {@code null} if no such header is present.
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
   * Posts {@code body} to {@code adminApiBase + path}, reads the JSON response body, and returns the value of the
   * {@code id} field. Use this for Authorization Services endpoints ({@code /authz/resource-server/policy/*},
   * {@code /authz/resource-server/permission/*}) which return the created resource in the body rather than a
   * {@code Location} header.
   *
   * @param path relative path under the admin API base
   * @param body request body (serialised to JSON by Jackson)
   * @return the {@code id} field from the response body
   * @throws KeycloakAdminException if no id is present in the response or on any HTTP error
   */
  private @NonNull String adminPostForId(final @NonNull String path, final @NonNull Object body) {
    return this.adminPostForId(path, body, true);
  }

  private @NonNull String adminPostForId(final @NonNull String path, final @NonNull Object body,
      final boolean retryOn401) {
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
   * Extracts the Keycloak group UUID from a {@code Location} header value by returning the substring after the last
   * {@code /}.
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
    final List<Map<String, Object>> children = new ArrayList<>();
    int first = 0;
    while (true) {
      final List<Map<String, Object>> page = this.adminGet(
          "/groups/" + groupId + "/children?briefRepresentation=false&first=" + first + "&max=" + PAGE_SIZE,
          new ParameterizedTypeReference<>() {});
      if (page == null || page.isEmpty()) {
        break;
      }
      children.addAll(page);
      if (page.size() < PAGE_SIZE) {
        break;
      }
      first += PAGE_SIZE;
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
   * Fetches all organization groups from the {@code orgs} top-level group, including each org's attached function
   * sub-groups.
   *
   * @return list of organizations; never {@code null}
   */
  public @NonNull List<OrganizationInfo> fetchAllOrganizationGroups() {
    final List<OrganizationInfo> allOrgGroups = new ArrayList<>();
    int first = 0;
    while (true) {
      final List<OrganizationInfo> page = this.fetchOrganizationGroupsPaged(first, PAGE_SIZE);
      if (page == null || page.isEmpty()) {
        break;
      }
      allOrgGroups.addAll(page);
      if (page.size() < PAGE_SIZE) {
        break;
      }
      first += PAGE_SIZE;
    }
    return allOrgGroups;
  }

  /**
   * Fetches a single page of organization groups from Keycloak.
   *
   * @param first offset (0-based)
   * @param max maximum number of results to return
   * @return list of organizations for the requested page; never {@code null}
   */
  public @NonNull List<OrganizationInfo> fetchOrganizationGroupsPaged(final int first, final int max) {
    final String orgsGroupId = this.findTopLevelGroupId("orgs");
    final List<Map<String, Object>> orgGroups = this.adminGet(
        "/groups/" + orgsGroupId + "/children?briefRepresentation=false&first=" + first + "&max=" + max,
        new ParameterizedTypeReference<>() {});
    if (orgGroups == null) {
      return List.of();
    }
    return this.mapOrgGroups(orgGroups);
  }

  /**
   * Returns the total number of organization groups directly under the {@code orgs} top-level group.
   *
   * <p>Uses paged fetches with {@code briefRepresentation=true} for compatibility with
   * Keycloak versions that do not expose the {@code /children/count} endpoint.</p>
   *
   * @return total organization count
   */
  public int fetchOrganizationGroupCount() {
    final String orgsGroupId = this.findTopLevelGroupId("orgs");
    int count = 0;
    int first = 0;
    while (true) {
      final List<Map<String, Object>> page = this.adminGet(
          "/groups/" + orgsGroupId + "/children?briefRepresentation=true&first=" + first + "&max=" + PAGE_SIZE,
          new ParameterizedTypeReference<>() {});
      if (page == null || page.isEmpty()) {
        break;
      }
      count += page.size();
      if (page.size() < PAGE_SIZE) {
        break;
      }
      first += PAGE_SIZE;
    }
    return count;
  }

  /**
   * Fetches a single organization by its identifier.
   *
   * <p>Uses Keycloak's group search by name and filters to the exact path
   * {@code /orgs/{orgIdentifier}}. Handles both the flat response format of Keycloak 23+ and the hierarchical response
   * of older versions where matching sub-groups are nested inside their parent group under the {@code subGroups}
   * key.</p>
   *
   * @param orgIdentifier the organization identifier (10-digit number)
   * @return the organization, or empty if not found
   */
  public @NonNull Optional<OrganizationInfo> fetchOrganizationByIdentifier(
      final @NonNull String orgIdentifier) {

    final String encoded = URLEncoder.encode(orgIdentifier, StandardCharsets.UTF_8);
    final List<Map<String, Object>> groups = this.adminGet(
        "/groups?search=" + encoded + "&exact=true&briefRepresentation=false",
        new ParameterizedTypeReference<>() {});
    if (groups == null || groups.isEmpty()) {
      return Optional.empty();
    }
    final String expectedPath = "/orgs/" + orgIdentifier;

    // Keycloak 23+: search returns a flat list — the org group is directly in the result.
    Map<String, Object> orgGroup = groups.stream()
        .filter(g -> expectedPath.equals(getString(g, "path")))
        .findFirst()
        .orElse(null);

    // Older Keycloak: search returns top-level groups with matching children nested under
    // "subGroups". Subgroup entries may use brief representation (no attributes), so we
    // fetch the full group representation by ID before mapping.
    if (orgGroup == null) {
      final String groupId = groups.stream()
          .flatMap(g -> getSubGroups(g).stream())
          .filter(g -> expectedPath.equals(getString(g, "path")))
          .map(g -> getString(g, "id"))
          .filter(Objects::nonNull)
          .findFirst()
          .orElse(null);
      if (groupId != null) {
        orgGroup = this.adminGet("/groups/" + groupId, new ParameterizedTypeReference<>() {});
      }
    }

    if (orgGroup == null) {
      return Optional.empty();
    }
    final List<OrganizationInfo> mapped = this.mapOrgGroups(List.of(orgGroup));
    return mapped.isEmpty() ? Optional.empty() : Optional.of(mapped.getFirst());
  }

  @SuppressWarnings("unchecked")
  private static @NonNull List<Map<String, Object>> getSubGroups(final @NonNull Map<String, Object> group) {
    return group.get("subGroups") instanceof final List<?> list
        ? (List<Map<String, Object>>) list
        : List.of();
  }

  /**
   * Returns the sorted list of organization identifiers that have the given function attached.
   *
   * <p>Uses Keycloak's global group search for groups named {@code functionId}, then filters
   * to those at path depth {@code /orgs/{orgId}/{functionId}}. The result is sorted for stable pagination.</p>
   *
   * @param functionId the function identifier
   * @return sorted list of org identifiers; never {@code null}
   */
  public @NonNull List<String> fetchOrgIdentifiersForFunction(final @NonNull String functionId) {
    final String encoded = URLEncoder.encode(functionId, StandardCharsets.UTF_8);
    final List<Map<String, Object>> groups = this.adminGet(
        "/groups?search=" + encoded + "&exact=true&briefRepresentation=true",
        new ParameterizedTypeReference<>() {});
    if (groups == null || groups.isEmpty()) {
      return List.of();
    }
    return groups.stream()
        .map(g -> getString(g, "path"))
        .filter(Objects::nonNull)
        .filter(path -> {
          final String[] parts = path.split("/");
          // Expect exactly: ["", "orgs", "{orgId}", "{functionId}"]
          return parts.length == 4
              && "orgs".equals(parts[1])
              && functionId.equals(parts[3]);
        })
        .map(path -> path.split("/")[2])
        .distinct()
        .sorted()
        .toList();
  }

  private @NonNull List<OrganizationInfo> mapOrgGroups(final @NonNull List<Map<String, Object>> orgGroups) {
    final List<OrganizationInfo> result = new ArrayList<>();
    for (final Map<String, Object> org : orgGroups) {
      final String groupId = getString(org, "id");

      String orgIdentifier = getFirstAttr(org, "organization_identifier");
      if (orgIdentifier == null) {
        orgIdentifier = getString(org, "name");
      }

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
   * Checks whether an organization with the given identifier already exists under the {@code orgs} top-level group.
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
   * Creates a new organization group under the {@code orgs} top-level group, along with the standard {@code _admin},
   * {@code _write}, and {@code _read} sub-groups.
   *
   * <p>No rollback is performed if a sub-group creation fails — the caller should treat a
   * {@link KeycloakAdminException} as a partial-failure signal and investigate manually.</p>
   *
   * @param orgIdentifier the organization number (10 digits), used as the group name
   * @param nameSv Swedish organization name
   * @param nameEn English organization name
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
   * Checks whether a function with the given identifier exists under the {@code functions} top-level group.
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
   * @param functionId the function identifier (group name), must match {@code [a-z0-9_-]+}
   * @param nameSv Swedish display name
   * @param nameEn English display name
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
   * @param functionId the function identifier (group name)
   * @param nameSv new Swedish display name
   * @param nameEn new English display name
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
    }
    else {
      attrs.remove("description#sv");
    }
    if (descriptionEn != null && !descriptionEn.isBlank()) {
      attrs.put("description#en", List.of(descriptionEn));
    }
    else {
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
   * @throws KeycloakAdminException if the canonical function group is not found or on unexpected API errors
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

    // Only clients that handle this function hold artifacts for it
    final List<ManagedClientInfo> clients = this.resolveManagedClientsForFunction(functionId);

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
      final Map<String, String> realmScopeIds = this.fetchRealmClientScopeIds();

      for (final ManagedClientInfo client : clients) {
        final int removed = this.removeClientFunctionArtifacts(
            client.uuid(), orgIdentifier, functionId, realmScopeIds);
        log.debug("Removed {} artifacts from client '{}' for '{}:{}'",
            removed, client.clientId(), orgIdentifier, functionId);
      }

      // Delete realm-level client scopes
      for (final String level : RIGHT_LEVELS) {
        final String scope = scopeName(orgIdentifier, functionId, level);
        final String scopeId = realmScopeIds.get(scope);
        if (scopeId != null) {
          this.adminDelete("/client-scopes/" + scopeId);
          log.debug("Deleted realm client scope '{}'", scope);
        }
        else {
          log.debug("Realm client scope '{}' not found — skipping", scope);
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
   * @param functionId the function identifier
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
   * @param functionId the function identifier
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

    // Resolve the managed clients that handle this function
    final List<ManagedClientInfo> clients = this.resolveManagedClientsForFunction(functionId);

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

    // Step 4 — Ensure the three realm client scopes exist. They are shared between clients, so
    // they are created even when no managed client handles the function.
    final Map<String, String> realmScopeIds = this.fetchRealmClientScopeIds();
    for (final String level : RIGHT_LEVELS) {
      this.ensureRealmClientScope(scopeName(orgIdentifier, functionId, level), realmScopeIds);
    }

    // Step 5 — Create the per-client authz scopes, group policies, scope permissions and optional
    // client scope assignments
    for (final ManagedClientInfo client : clients) {
      final ClientArtifactState state = this.fetchClientArtifactState(client.uuid());
      final int created = this.ensureFunctionArtifacts(
          client.uuid(), orgIdentifier, functionId, realmScopeIds, state);
      log.debug("Created {} artifacts on client '{}' for '{}:{}'",
          created, client.clientId(), orgIdentifier, functionId);
    }

    log.info("Function '{}' attached to organization '{}' in Keycloak", functionId, orgIdentifier);
  }

  /**
   * Resolves the Keycloak group UUID for the given organization by looking it up as a direct child of the {@code orgs}
   * top-level group.
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
   * Resolves the full set of managed Keycloak clients by combining two sources:
   *
   * <ol>
   *   <li><strong>Dynamic discovery</strong> — pages through {@code GET /clients} and filters
   *       clients whose {@code attributes.iam_admin_managed} equals {@code "true"}.</li>
   *   <li><strong>Fallback config</strong> — the client IDs listed in
   *       {@code IamAdminProperties.authzClientIds} (if any).</li>
   * </ol>
   *
   * <p>The result is the union of both sources with duplicates eliminated. If the result is empty
   * a WARN is logged.</p>
   *
   * <p>Each client carries the functions declared in its {@code client_functions} attribute. Use
   * {@link ManagedClientInfo#handles(String)} to decide whether a client should receive the
   * artifacts for a given function.</p>
   *
   * @return list of managed clients; never {@code null}
   * @throws KeycloakAdminException if a fallback client ID cannot be resolved, or on any Keycloak
   *     API error
   */
  public @NonNull List<ManagedClientInfo> resolveIamAdminManagedClients() {
    final LinkedHashMap<String, ManagedClientInfo> result = new LinkedHashMap<>();
    final List<Map<String, Object>> allClients = this.fetchAllClients();

    // Source 1 — dynamic discovery via iam_admin_managed attribute
    for (final Map<String, Object> client : allClients) {
      if ("true".equals(clientAttribute(client, "iam_admin_managed"))) {
        final ManagedClientInfo info = this.toManagedClientInfo(client);
        if (info != null && info.oidcClient()) {
          result.put(info.uuid(), info);
        }
      }
    }
    log.debug("Managed clients discovered via iam_admin_managed attribute: {}", result.size());

    // Source 2 — fallback config
    final List<String> fallbackIds = this.properties.getAuthzClientIds();
    if (fallbackIds != null && !fallbackIds.isEmpty()) {
      log.debug("Resolving {} fallback authz-client-ids", fallbackIds.size());
      for (final String clientId : fallbackIds) {
        final ManagedClientInfo info = allClients.stream()
            .filter(c -> clientId.equals(getString(c, "clientId")))
            .map(this::toManagedClientInfo)
            .filter(Objects::nonNull)
            .findFirst()
            .orElseThrow(() -> new KeycloakAdminException("Keycloak client not found: " + clientId));
        result.putIfAbsent(info.uuid(), info);
      }
    }

    if (result.isEmpty()) {
      log.warn("No managed Keycloak clients found (neither via iam_admin_managed attribute"
          + " nor authz-client-ids) — no authz artifacts will be created/deleted");
    }

    return new ArrayList<>(result.values());
  }

  /**
   * Resolves the resource servers the application administers, i.e. clients carrying
   * {@code iam_admin_resource_server=true}.
   *
   * <p>A resource server is never reconciled — it holds no scopes, policies or permissions. Only
   * its {@code client_functions} attribute matters, and that is read by {@code resource-aud-plugin}
   * when the client is named in the OAuth2 {@code resource} parameter.</p>
   *
   * @return list of resource servers; never {@code null}
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public @NonNull List<ManagedClientInfo> resolveResourceServers() {
    final List<ManagedClientInfo> result = new ArrayList<>();
    for (final Map<String, Object> client : this.fetchAllClients()) {
      if ("true".equals(clientAttribute(client, "iam_admin_resource_server"))) {
        final ManagedClientInfo info = this.toManagedClientInfo(client);
        if (info != null) {
          result.add(info);
        }
      }
    }
    log.debug("Resource servers discovered via iam_admin_resource_server attribute: {}", result.size());
    return result;
  }

  /**
   * Resolves everything the application administers: the managed clients and the resource servers.
   *
   * @return the administered clients; never {@code null}
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public @NonNull List<ManagedClientInfo> resolveAdministeredClients() {
    final List<ManagedClientInfo> result = new ArrayList<>(this.resolveIamAdminManagedClients());
    result.addAll(this.resolveResourceServers());
    return result;
  }

  /**
   * Returns the managed clients that handle the given function, i.e. those that should receive the
   * KeyCloak artifacts belonging to it.
   *
   * @param functionId the function identifier
   * @return the matching managed clients; never {@code null}
   * @throws KeycloakAdminException on any Keycloak API error
   */
  private @NonNull List<ManagedClientInfo> resolveManagedClientsForFunction(
      final @NonNull String functionId) {

    final List<ManagedClientInfo> all = this.resolveIamAdminManagedClients();
    final List<ManagedClientInfo> matching = all.stream()
        .filter(c -> c.handles(functionId))
        .toList();

    if (matching.size() < all.size()) {
      log.debug("{} of {} managed clients handle function '{}'", matching.size(), all.size(), functionId);
    }
    return matching;
  }

  /**
   * Pages through {@code GET /clients} and returns every client representation in the realm.
   *
   * @return the client representations; never {@code null}
   * @throws KeycloakAdminException on any Keycloak API error
   */
  private @NonNull List<Map<String, Object>> fetchAllClients() {
    final List<Map<String, Object>> all = new ArrayList<>();
    int first = 0;
    while (true) {
      final List<Map<String, Object>> page = this.adminGet(
          "/clients?first=" + first + "&max=" + PAGE_SIZE, new ParameterizedTypeReference<>() {});
      if (page == null || page.isEmpty()) {
        break;
      }
      all.addAll(page);
      if (page.size() < PAGE_SIZE) {
        break;
      }
      first += PAGE_SIZE;
    }
    log.debug("Fetched {} Keycloak clients", all.size());
    return all;
  }

  /**
   * Converts a Keycloak client representation into a {@link ManagedClientInfo}.
   *
   * @param client the client representation
   * @return the managed client, or {@code null} if the representation has no id or clientId
   */
  /**
   * Expands a redirect URI that Keycloak stores as a path into the complete URI it resolves to.
   *
   * <p>Keycloak permits a redirect URI given as a path only and resolves it against the client root
   * URL. Showing the stored value raw leaves the user unable to see where the callback actually
   * goes, so it is joined here with exactly one {@code /} between the two parts. A trailing wildcard
   * is carried along untouched.</p>
   *
   * <p>Where the client has no root URL the path is returned unchanged. Keycloak would resolve it
   * against the auth server root URL, but that is not known here, and guessing would display a
   * callback that is not the real one. Such a URI is rejected on save, so the user has to complete
   * it first.</p>
   *
   * @param uri the stored redirect URI
   * @param rootUrl the client's root URL, or {@code null} if it has none
   * @return the complete redirect URI, or the input unchanged if it cannot be expanded
   */
  static @NonNull String expandRedirectUri(final @NonNull String uri, final @Nullable String rootUrl) {
    if (rootUrl == null || rootUrl.isBlank() || uri.isBlank()) {
      return uri;
    }
    try {
      if (new URI(uri).isAbsolute()) {
        return uri;
      }
    }
    catch (final URISyntaxException e) {
      return uri;
    }
    final String base = rootUrl.endsWith("/") ? rootUrl.substring(0, rootUrl.length() - 1) : rootUrl;
    return uri.startsWith("/") ? base + uri : base + "/" + uri;
  }

  private @Nullable ManagedClientInfo toManagedClientInfo(final @NonNull Map<String, Object> client) {
    final String uuid = getString(client, "id");
    final String clientId = getString(client, "clientId");
    if (uuid == null || clientId == null) {
      log.debug("Skipping Keycloak client representation without id or clientId");
      return null;
    }
    final String rootUrl = getString(client, "rootUrl");
    final List<String> redirectUris = client.get("redirectUris") instanceof final List<?> uris
        ? uris.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .map(uri -> expandRedirectUri(uri, rootUrl))
            .toList()
        : List.of();
    return new ManagedClientInfo(
        uuid,
        clientId,
        getString(client, "name"),
        "true".equals(clientAttribute(client, "iam_admin_managed")),
        "true".equals(clientAttribute(client, "iam_admin_resource_server")),
        ManagedClientInfo.parseFunctions(clientAttribute(client, "client_functions")),
        redirectUris,
        "true".equals(clientAttribute(client, "use.jwks.url")) ? clientAttribute(client, "jwks.url") : null,
        "true".equals(clientAttribute(client, "use.jwks.string")) ? clientAttribute(client, "jwks.string") : null,
        this.serviceAccountWanted(client, uuid),
        orgRightsClaimEnabled(client, "id.token.claim"),
        orgRightsClaimEnabled(client, "access.token.claim"),
        !Boolean.FALSE.equals(client.get("enabled")));
  }

  /**
   * Tells whether a client is meant to have a service account user.
   *
   * <p>The {@link #SERVICE_ACCOUNT_ATTRIBUTE} attribute answers it for every client this
   * application has written. A client provisioned outside it carries no such attribute, and is
   * read from {@code serviceAccountsEnabled}.</p>
   *
   * @param client the client representation
   * @param clientUuid the Keycloak UUID of the client
   * @return {@code true} if the client keeps a service account user
   */
  private boolean serviceAccountWanted(
      final @NonNull Map<String, Object> client, final @NonNull String clientUuid) {

    final String attribute = clientAttribute(client, SERVICE_ACCOUNT_ATTRIBUTE);
    if (attribute != null) {
      return "true".equals(attribute);
    }
    if (!Boolean.TRUE.equals(client.get("serviceAccountsEnabled"))) {
      return false;
    }
    // A client provisioned outside this application carries no attribute, and neither the flag
    // nor the user proves anything: Keycloak turns the flag on and creates the user by itself for
    // every client with Authorization Services enabled. A service account that was actually asked
    // for is the one carrying the realm-management roles the scripts assign to it
    return this.hasAdminRoleMappings(clientUuid);
  }

  /**
   * Tells whether a client's service account user holds {@code realm-management} roles, i.e.
   * whether it was created deliberately rather than as a by-product of Authorization Services.
   *
   * @param clientUuid the Keycloak UUID of the client
   * @return {@code true} if the client has a service account user with {@code realm-management}
   *     role mappings
   */
  private boolean hasAdminRoleMappings(final @NonNull String clientUuid) {
    final String userId = this.findServiceAccountUserId(clientUuid);
    if (userId == null) {
      return false;
    }
    try {
      final Map<String, Object> mappings = this.adminGet(
          "/users/" + userId + "/role-mappings", new ParameterizedTypeReference<>() {});
      return mappings != null
          && mappings.get("clientMappings") instanceof final Map<?, ?> clientMappings
          && clientMappings.containsKey("realm-management");
    }
    catch (final KeycloakAdminException e) {
      log.debug("Could not read role mappings for the service account of client {}: {}",
          clientUuid, e.getMessage());
      return false;
    }
  }

  /**
   * Reads a claim inclusion flag from the {@code org-rights-mapper} of a Keycloak client
   * representation.
   *
   * <p>A representation that carries no such mapper is reported as emitting the claim: that is
   * what a managed client is given when it is registered, and it is also the safe reading for a
   * client that has not been given its mappers yet.</p>
   *
   * @param client the client representation
   * @param configKey the mapper configuration key, {@code id.token.claim} or
   *     {@code access.token.claim}
   * @return {@code false} only if the mapper is present and has the flag set to {@code false}
   */
  private static boolean orgRightsClaimEnabled(
      final @NonNull Map<String, Object> client, final @NonNull String configKey) {

    if (!(client.get("protocolMappers") instanceof final List<?> mappers)) {
      return true;
    }
    for (final Object mapper : mappers) {
      if (mapper instanceof final Map<?, ?> m
          && ORG_RIGHTS_MAPPER.equals(m.get("name"))
          && m.get("config") instanceof final Map<?, ?> config) {
        return !"false".equals(config.get(configKey));
      }
    }
    return true;
  }

  /**
   * Reads a single attribute from a Keycloak client representation.
   *
   * @param client the client representation
   * @param name the attribute name
   * @return the attribute value, or {@code null} if the client has no attributes or no such
   *     attribute
   */
  private static @Nullable String clientAttribute(
      final @NonNull Map<String, Object> client, final @NonNull String name) {

    if (client.get("attributes") instanceof final Map<?, ?> attrs) {
      return attrs.get(name) instanceof final String value ? value : null;
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Managed client administration
  // ---------------------------------------------------------------------------

  /**
   * Looks up a managed client by its OAuth2 client_id.
   *
   * @param clientId the OAuth2 client_id
   * @return the managed client, or empty if no such client exists or it is not managed
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public @NonNull Optional<ManagedClientInfo> findManagedClient(final @NonNull String clientId) {
    return this.resolveIamAdminManagedClients().stream()
        .filter(c -> clientId.equals(c.clientId()))
        .findFirst();
  }

  /**
   * Looks up a managed client by its Keycloak UUID.
   *
   * <p>The per-client REST endpoints address clients by UUID rather than by client_id: a client_id
   * is a URL, and URL-encoding one into a path segment is rejected by Spring Security's strict
   * HTTP firewall before the request reaches the controller.</p>
   *
   * @param uuid the Keycloak UUID
   * @return the managed client, or empty if no such client exists or it is not managed
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public @NonNull Optional<ManagedClientInfo> findManagedClientByUuid(final @NonNull String uuid) {
    return this.resolveAdministeredClients().stream()
        .filter(c -> uuid.equals(c.uuid()))
        .findFirst();
  }

  /**
   * Tells whether a client with the given client_id exists in the realm, managed or not.
   *
   * @param clientId the OAuth2 client_id
   * @return {@code true} if the client exists
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public boolean clientExists(final @NonNull String clientId) {
    return this.fetchClientByClientId(clientId) != null;
  }

  /**
   * Registers a managed OIDC client in Keycloak.
   *
   * <p>The resulting client is identical to what {@code add-oidc-client.sh} produces:
   * {@code private_key_jwt} client authentication, Authorization Services enabled, the
   * {@code org-rights}, {@code scope-org-identifier} and {@code resource-audience} protocol
   * mappers, and the {@code naturalPersonNumber} and {@code phone} optional client scopes. The
   * service account user Keycloak creates is deleted unless {@code serviceAccount} is set.</p>
   *
   * <p>No org/function artifacts are created here — the caller reconciles the client afterwards.
   * A creation that fails part-way therefore leaves a client that the next reconciliation
   * repairs, rather than a broken one.</p>
   *
   * @param clientId the OAuth2 client_id
   * @param name the display name, or {@code null}
   * @param redirectUris the redirect URIs
   * @param functions the functions the client handles
   * @param jwksUri the JWKS URI, or {@code null} if {@code jwksString} is given
   * @param jwksString the inline JWK Set, or {@code null} if {@code jwksUri} is given
   * @param serviceAccount whether to keep the service account user
   * @param orgRightsIdToken whether {@code org_rights} is emitted in the ID token
   * @param orgRightsAccessToken whether {@code org_rights} is emitted in the access token
   * @return the created client
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public @NonNull ManagedClientInfo createManagedClient(
      final @NonNull String clientId,
      final @Nullable String name,
      final boolean oidcClient,
      final boolean resourceServer,
      final @NonNull List<String> redirectUris,
      final @NonNull Set<String> functions,
      final @Nullable String jwksUri,
      final @Nullable String jwksString,
      final boolean serviceAccount,
      final boolean orgRightsIdToken,
      final boolean orgRightsAccessToken) {

    final Map<String, Object> creation = new LinkedHashMap<>();
    creation.put("clientId", clientId);
    creation.put("protocol", "openid-connect");
    creation.put("enabled", true);
    creation.put("publicClient", !oidcClient);
    creation.put("standardFlowEnabled", oidcClient);
    creation.put("implicitFlowEnabled", false);
    creation.put("directAccessGrantsEnabled", false);
    creation.put("serviceAccountsEnabled", oidcClient);
    if (oidcClient) {
      creation.put("clientAuthenticatorType", "client-jwt");
    }
    this.adminPost("/clients", creation);

    final Map<String, Object> client = this.fetchClientByClientId(clientId);
    if (client == null) {
      throw new KeycloakAdminException("Keycloak client '" + clientId + "' not found after creation");
    }
    final String clientUuid = getString(client, "id");
    if (clientUuid == null) {
      throw new KeycloakAdminException("Keycloak client '" + clientId + "' has no id");
    }
    log.debug("Client '{}' created with id {}", clientId, clientUuid);

    this.writeClientSettings(clientUuid, client, name, oidcClient, resourceServer,
        redirectUris, functions, jwksUri, jwksString, serviceAccount);

    if (oidcClient) {
      this.handleServiceAccount(clientUuid, clientId, serviceAccount);
      this.addProtocolMappers(clientUuid, orgRightsIdToken, orgRightsAccessToken);
      this.addBaseOptionalScopes(clientUuid);
    }

    log.info("Client '{}' registered in Keycloak — oidcClient={}, resourceServer={}",
        clientId, oidcClient, resourceServer);
    return this.findManagedClient(clientId).orElseThrow(
        () -> new KeycloakAdminException("Client '" + clientId + "' is not managed after creation"));
  }

  /**
   * Updates the mutable settings of a managed client: display name, redirect URIs, handled
   * functions and client keys. The client_id is immutable.
   *
   * @param clientId the OAuth2 client_id
   * @param name the display name, or {@code null}
   * @param redirectUris the redirect URIs
   * @param functions the functions the client handles
   * @param jwksUri the JWKS URI, or {@code null} if {@code jwksString} is given
   * @param jwksString the inline JWK Set, or {@code null} if {@code jwksUri} is given
   * @param serviceAccount whether the client keeps a service account user
   * @param orgRightsIdToken whether {@code org_rights} is emitted in the ID token
   * @param orgRightsAccessToken whether {@code org_rights} is emitted in the access token
   * @return the updated client
   * @throws KeycloakAdminException if the client does not exist, or on any Keycloak API error
   */
  public @NonNull ManagedClientInfo updateManagedClient(
      final @NonNull String clientId,
      final @Nullable String name,
      final boolean oidcClient,
      final boolean resourceServer,
      final @NonNull List<String> redirectUris,
      final @NonNull Set<String> functions,
      final @Nullable String jwksUri,
      final @Nullable String jwksString,
      final boolean serviceAccount,
      final boolean orgRightsIdToken,
      final boolean orgRightsAccessToken) {

    final Map<String, Object> client = this.fetchClientByClientId(clientId);
    if (client == null) {
      throw new KeycloakAdminException("Keycloak client not found: " + clientId);
    }
    final String clientUuid = getString(client, "id");
    if (clientUuid == null) {
      throw new KeycloakAdminException("Keycloak client '" + clientId + "' has no id");
    }

    final boolean wasOidcClient = "true".equals(clientAttribute(client, "iam_admin_managed"));
    this.writeClientSettings(clientUuid, client, name, oidcClient, resourceServer,
        redirectUris, functions, jwksUri, jwksString, serviceAccount);

    if (oidcClient) {
      // The service account itself is never touched here — only the attribute recording it, which
      // writeClientSettings has already carried over
      if (!wasOidcClient) {
        // The client is taking on the OIDC client role — it needs the mappers and base scopes
        // that a client registered in that role gets from the start
        this.addProtocolMappers(clientUuid, orgRightsIdToken, orgRightsAccessToken);
        this.addBaseOptionalScopes(clientUuid);
      }
      else {
        this.writeOrgRightsMapperConfig(clientUuid, orgRightsIdToken, orgRightsAccessToken);
      }
    }

    log.info("Client '{}' updated — oidcClient={}, resourceServer={}",
        clientId, oidcClient, resourceServer);
    return this.findManagedClient(clientId).orElseThrow(
        () -> new KeycloakAdminException("Client '" + clientId + "' is not managed after update"));
  }

  /**
   * Permanently deletes a client from Keycloak, together with its Authorization Services
   * artifacts. The realm-level client scopes are shared between clients and are left intact.
   *
   * @param clientId the OAuth2 client_id
   * @throws KeycloakAdminException if the client does not exist, or on any Keycloak API error
   */
  public void deleteManagedClient(final @NonNull String clientId) {
    final Map<String, Object> client = this.fetchClientByClientId(clientId);
    if (client == null) {
      throw new KeycloakAdminException("Keycloak client not found: " + clientId);
    }
    final String clientUuid = getString(client, "id");
    if (clientUuid == null) {
      throw new KeycloakAdminException("Keycloak client '" + clientId + "' has no id");
    }
    this.adminDelete("/clients/" + clientUuid);
    log.info("Managed client '{}' deleted from Keycloak", clientId);
  }

  /**
   * Applies the settings the IAM Admin application owns onto a client, merging them into the
   * current representation so that unrelated settings are preserved.
   *
   * <p>The two roles are written independently. The OIDC client role decides the client's shape —
   * confidential with {@code private_key_jwt}, standard flow and Authorization Services when on;
   * public with every flow disabled when off. The resource server role only adds a marker
   * attribute; being an audience target requires no client settings of its own.</p>
   *
   * <p>Turning the OIDC client role off disables Authorization Services, which makes Keycloak
   * discard the client's policies and permissions.</p>
   *
   * @param clientUuid the Keycloak UUID of the client
   * @param current the current client representation
   * @param name the display name, or {@code null}
   * @param oidcClient whether the client obtains org-scoped tokens
   * @param resourceServer whether the client may be named as an OAuth2 {@code resource} target
   * @param redirectUris the redirect URIs; ignored unless {@code oidcClient}
   * @param functions the functions the client handles
   * @param jwksUri the JWKS URI, or {@code null}; ignored unless {@code oidcClient}
   * @param jwksString the inline JWK Set, or {@code null}; ignored unless {@code oidcClient}
   * @param serviceAccount whether the client keeps a service account user; recorded in
   *     {@link #SERVICE_ACCOUNT_ATTRIBUTE}, never acted on. Ignored unless {@code oidcClient}
   * @throws KeycloakAdminException on any Keycloak API error
   */
  private void writeClientSettings(
      final @NonNull String clientUuid,
      final @NonNull Map<String, Object> current,
      final @Nullable String name,
      final boolean oidcClient,
      final boolean resourceServer,
      final @NonNull List<String> redirectUris,
      final @NonNull Set<String> functions,
      final @Nullable String jwksUri,
      final @Nullable String jwksString,
      final boolean serviceAccount) {

    final Map<String, Object> body = new LinkedHashMap<>(current);
    body.put("implicitFlowEnabled", false);
    body.put("directAccessGrantsEnabled", false);
    body.put("publicClient", !oidcClient);
    body.put("standardFlowEnabled", oidcClient);
    body.put("serviceAccountsEnabled", oidcClient);
    body.put("authorizationServicesEnabled", oidcClient);
    if (name != null) {
      body.put("name", name);
    }

    final Map<String, Object> attributes = current.get("attributes") instanceof final Map<?, ?> attrs
        ? new LinkedHashMap<>(castAttributes(attrs)) : new LinkedHashMap<>();
    attributes.put("client_functions", String.join(",", functions));

    if (oidcClient) {
      attributes.put(SERVICE_ACCOUNT_ATTRIBUTE, String.valueOf(serviceAccount));
      // The root URL is never written. It is read to expand relative redirect URIs for display and
      // is otherwise left exactly as the client has it, including having none.
      body.put("redirectUris", redirectUris);
      body.put("clientAuthenticatorType", "client-jwt");
      attributes.put("iam_admin_managed", "true");
      if (jwksUri != null) {
        attributes.put("use.jwks.url", "true");
        attributes.put("jwks.url", jwksUri);
        attributes.remove("use.jwks.string");
        attributes.remove("jwks.string");
      }
      else {
        attributes.put("use.jwks.string", "true");
        attributes.put("jwks.string", jwksString);
        attributes.remove("use.jwks.url");
        attributes.remove("jwks.url");
      }
    }
    else {
      body.put("redirectUris", List.of());
      attributes.remove(SERVICE_ACCOUNT_ATTRIBUTE);
      attributes.remove("iam_admin_managed");
      attributes.remove("use.jwks.url");
      attributes.remove("jwks.url");
      attributes.remove("use.jwks.string");
      attributes.remove("jwks.string");
    }

    if (resourceServer) {
      attributes.put("iam_admin_resource_server", "true");
    }
    else {
      attributes.remove("iam_admin_resource_server");
    }

    body.put("attributes", attributes);

    this.adminPutWithBody("/clients/" + clientUuid, body);
    log.debug("Client settings written for {} — oidcClient={}, resourceServer={}",
        clientUuid, oidcClient, resourceServer);
  }

  /**
   * Keeps or deletes the service account user Keycloak creates for a confidential client.
   *
   * @param clientUuid the Keycloak UUID of the client
   * @param clientId the OAuth2 client_id, used for logging
   * @param keep whether the service account user is wanted
   * @throws KeycloakAdminException on any Keycloak API error
   */
  private void handleServiceAccount(
      final @NonNull String clientUuid, final @NonNull String clientId, final boolean keep) {

    if (keep) {
      log.debug("Service account kept for client '{}'", clientId);
      return;
    }
    final String userId = this.findServiceAccountUserId(clientUuid);
    if (userId == null) {
      log.debug("No service account user found for client '{}' — nothing to delete", clientId);
      return;
    }
    this.adminDelete("/users/" + userId);
    log.debug("Service account user deleted for client '{}'", clientId);
  }

  /**
   * Looks up the service account user of a client.
   *
   * <p>Keycloak answers {@code 404} for a client that has none, which is an expected outcome
   * here rather than an error.</p>
   *
   * @param clientUuid the Keycloak UUID of the client
   * @return the user id, or {@code null} if the client has no service account user
   */
  private @Nullable String findServiceAccountUserId(final @NonNull String clientUuid) {
    try {
      final Map<String, Object> user = this.adminGet(
          "/clients/" + clientUuid + "/service-account-user", new ParameterizedTypeReference<>() {});
      return user == null ? null : getString(user, "id");
    }
    catch (final KeycloakAdminException e) {
      log.debug("No service account user for client {}: {}", clientUuid, e.getMessage());
      return null;
    }
  }

  /**
   * Adds the protocol mappers a managed client needs.
   *
   * <p>The {@code resource-audience-mapper} depends on the {@code resource-aud-plugin} being
   * deployed in Keycloak. When it is not, its creation fails and a WARN is logged — the client is
   * still usable, but cannot target resource servers via the {@code resource} parameter.</p>
   *
   * @param clientUuid the Keycloak UUID of the client
   * @param orgRightsIdToken whether {@code org_rights} is emitted in the ID token
   * @param orgRightsAccessToken whether {@code org_rights} is emitted in the access token
   * @throws KeycloakAdminException on any Keycloak API error
   */
  private void addProtocolMappers(
      final @NonNull String clientUuid,
      final boolean orgRightsIdToken,
      final boolean orgRightsAccessToken) {

    final String path = "/clients/" + clientUuid + "/protocol-mappers/models";
    final List<Map<String, Object>> existing = this.adminGet(path, new ParameterizedTypeReference<>() {});
    final Set<String> present = namesOf(existing == null ? List.of() : existing);

    if (!present.contains(ORG_RIGHTS_MAPPER)) {
      this.adminPost(path, Map.of(
          "name", ORG_RIGHTS_MAPPER,
          "protocol", "openid-connect",
          "protocolMapper", ORG_RIGHTS_MAPPER,
          "consentRequired", false,
          "config", Map.of(
              "id.token.claim", String.valueOf(orgRightsIdToken),
              "access.token.claim", String.valueOf(orgRightsAccessToken))));
      log.debug("Added {} to client {}", ORG_RIGHTS_MAPPER, clientUuid);
    }

    if (!present.contains("scope-org-identifier-mapper")) {
      this.adminPost(path, Map.of(
          "name", "scope-org-identifier-mapper",
          "protocol", "openid-connect",
          "protocolMapper", "scope-org-identifier-mapper",
          "consentRequired", false,
          "config", Map.of("id.token.claim", "false", "access.token.claim", "true")));
      log.debug("Added scope-org-identifier-mapper to client {}", clientUuid);
    }

    if (!present.contains("resource-audience-mapper")) {
      try {
        this.adminPost(path, Map.of(
            "name", "resource-audience-mapper",
            "protocol", "openid-connect",
            "protocolMapper", "resource-audience-mapper",
            "consentRequired", false,
            "config", Map.of("id.token.claim", "false", "access.token.claim", "true")));
        log.debug("Added resource-audience-mapper to client {}", clientUuid);
      }
      catch (final KeycloakAdminException e) {
        log.warn("Could not add 'resource-audience-mapper' to client {} — is the resource-aud-plugin"
            + " deployed and has Keycloak been rebuilt? {}", clientUuid, e.getMessage());
      }
    }
  }

  /**
   * Writes the claim inclusion flags onto a client's existing {@code org-rights-mapper}.
   *
   * <p>A client that has no such mapper is left alone: {@link #addProtocolMappers} creates it with
   * the wanted configuration, and a client that never received it is repaired there.</p>
   *
   * @param clientUuid the Keycloak UUID of the client
   * @param orgRightsIdToken whether {@code org_rights} is emitted in the ID token
   * @param orgRightsAccessToken whether {@code org_rights} is emitted in the access token
   * @throws KeycloakAdminException on any Keycloak API error
   */
  private void writeOrgRightsMapperConfig(
      final @NonNull String clientUuid,
      final boolean orgRightsIdToken,
      final boolean orgRightsAccessToken) {

    final String path = "/clients/" + clientUuid + "/protocol-mappers/models";
    final List<Map<String, Object>> mappers = this.adminGet(path, new ParameterizedTypeReference<>() {});
    if (mappers == null) {
      return;
    }
    for (final Map<String, Object> mapper : mappers) {
      if (!ORG_RIGHTS_MAPPER.equals(mapper.get("name"))) {
        continue;
      }
      final String mapperId = getString(mapper, "id");
      if (mapperId == null) {
        log.debug("Mapper '{}' on client {} has no id — cannot update it", ORG_RIGHTS_MAPPER, clientUuid);
        return;
      }
      final Map<String, Object> body = new LinkedHashMap<>(mapper);
      final Map<String, Object> config = mapper.get("config") instanceof final Map<?, ?> current
          ? new LinkedHashMap<>(castAttributes(current)) : new LinkedHashMap<>();
      config.put("id.token.claim", String.valueOf(orgRightsIdToken));
      config.put("access.token.claim", String.valueOf(orgRightsAccessToken));
      body.put("config", config);
      this.adminPutWithBody(path + "/" + mapperId, body);
      log.debug("Configured {} on client {} — idToken={}, accessToken={}",
          ORG_RIGHTS_MAPPER, clientUuid, orgRightsIdToken, orgRightsAccessToken);
      return;
    }
    log.debug("Client {} has no '{}' — nothing to configure", clientUuid, ORG_RIGHTS_MAPPER);
  }

  /**
   * Adds the optional client scopes every managed client needs, if the realm has them.
   *
   * @param clientUuid the Keycloak UUID of the client
   * @throws KeycloakAdminException on any Keycloak API error
   */
  private void addBaseOptionalScopes(final @NonNull String clientUuid) {
    final Map<String, String> realmScopeIds = this.fetchRealmClientScopeIds();
    for (final String scope : List.of("https://id.oidc.se/scope/naturalPersonNumber", "phone")) {
      final String scopeId = realmScopeIds.get(scope);
      if (scopeId == null) {
        log.warn("Client scope '{}' not found in realm — skipping. Has the realm been bootstrapped?", scope);
        continue;
      }
      this.adminPut("/clients/" + clientUuid + "/optional-client-scopes/" + scopeId);
      log.debug("Added optional client scope '{}' to client {}", scope, clientUuid);
    }
  }

  /**
   * Fetches a client representation by its OAuth2 client_id.
   *
   * @param clientId the OAuth2 client_id
   * @return the client representation, or {@code null} if no such client exists
   * @throws KeycloakAdminException on any Keycloak API error
   */
  private @Nullable Map<String, Object> fetchClientByClientId(final @NonNull String clientId) {
    final URI uri = UriComponentsBuilder.fromUriString(this.adminApiBase + "/clients")
        .queryParam("clientId", clientId)
        .queryParam("exact", "true")
        .build()
        .toUri();
    final List<Map<String, Object>> clients = this.adminGet(uri, new ParameterizedTypeReference<>() {});
    return clients == null || clients.isEmpty() ? null : clients.getFirst();
  }

  /**
   * Casts a client's attribute map to its declared type.
   *
   * @param attributes the raw attribute map
   * @return the attribute map
   */
  @SuppressWarnings("unchecked")
  private static @NonNull Map<String, Object> castAttributes(final @NonNull Map<?, ?> attributes) {
    return (Map<String, Object>) attributes;
  }

  // ---------------------------------------------------------------------------
  // Artifact naming and reconciliation
  // ---------------------------------------------------------------------------

  /** The rights levels an org/function combination is expanded into. */
  public static final List<String> RIGHT_LEVELS = List.of("read", "write", "admin");

  /**
   * Returns the OAuth2 client scope name for an org/function/level combination.
   *
   * @param orgIdentifier the organization identifier
   * @param functionId the function identifier
   * @param level the rights level
   * @return the scope name, e.g. {@code 5590026042:demo:read}
   */
  public static @NonNull String scopeName(
      final @NonNull String orgIdentifier, final @NonNull String functionId, final @NonNull String level) {
    return orgIdentifier + ":" + functionId + ":" + level;
  }

  /**
   * Returns the Authorization Services group policy name for an org/function/level combination.
   *
   * @param orgIdentifier the organization identifier
   * @param functionId the function identifier
   * @param level the rights level
   * @return the policy name, e.g. {@code policy-5590026042-demo-read}
   */
  public static @NonNull String policyName(
      final @NonNull String orgIdentifier, final @NonNull String functionId, final @NonNull String level) {
    return "policy-" + orgIdentifier + "-" + functionId + "-" + level;
  }

  /**
   * Returns the Authorization Services scope permission name for an org/function/level combination.
   *
   * @param orgIdentifier the organization identifier
   * @param functionId the function identifier
   * @param level the rights level
   * @return the permission name, e.g. {@code permission-5590026042-demo-read}
   */
  public static @NonNull String permissionName(
      final @NonNull String orgIdentifier, final @NonNull String functionId, final @NonNull String level) {
    return "permission-" + orgIdentifier + "-" + functionId + "-" + level;
  }

  /**
   * Returns the group paths that grant the given rights level on an org/function combination.
   *
   * <p>A higher level always qualifies for a lower one: {@code _admin} grants write and read,
   * {@code _write} grants read. Both the org-wide groups and the function-specific groups are
   * included.</p>
   *
   * @param orgIdentifier the organization identifier
   * @param functionId the function identifier
   * @param level the rights level
   * @return the qualifying group paths
   */
  private static @NonNull List<String> qualifyingGroupPaths(
      final @NonNull String orgIdentifier, final @NonNull String functionId, final @NonNull String level) {

    final String org = "/orgs/" + orgIdentifier;
    final String func = org + "/" + functionId;
    return switch (level) {
      case "read" -> List.of(
          org + "/_read", org + "/_write", org + "/_admin",
          func + "/_read", func + "/_write", func + "/_admin");
      case "write" -> List.of(
          org + "/_write", org + "/_admin",
          func + "/_write", func + "/_admin");
      case "admin" -> List.of(
          org + "/_admin",
          func + "/_admin");
      default -> throw new KeycloakAdminException("Unknown rights level: " + level);
    };
  }

  /**
   * Fetches every realm-level OAuth2 client scope, keyed by name.
   *
   * <p>The returned map is mutable and is updated in place by
   * {@link #ensureRealmClientScope(String, Map)}, so that a reconciliation run can create scopes
   * without re-fetching.</p>
   *
   * @return mutable map of scope name to Keycloak UUID; never {@code null}
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public @NonNull Map<String, String> fetchRealmClientScopeIds() {
    final List<Map<String, Object>> scopes = this.adminGet(
        "/client-scopes", new ParameterizedTypeReference<>() {});
    final Map<String, String> result = new LinkedHashMap<>();
    if (scopes != null) {
      for (final Map<String, Object> scope : scopes) {
        final String name = getString(scope, "name");
        final String id = getString(scope, "id");
        if (name != null && id != null) {
          result.put(name, id);
        }
      }
    }
    return result;
  }

  /**
   * Returns the Keycloak UUID of the realm client scope with the given name, creating the scope if
   * it does not exist.
   *
   * @param name the scope name
   * @param realmScopeIds map of known scope names to UUIDs, updated when a scope is created
   * @return the Keycloak UUID of the scope
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public @NonNull String ensureRealmClientScope(
      final @NonNull String name, final @NonNull Map<String, String> realmScopeIds) {

    final String existing = realmScopeIds.get(name);
    if (existing != null) {
      return existing;
    }
    final String created = this.createClientScope(name);
    realmScopeIds.put(name, created);
    log.debug("Created realm client scope '{}' with id {}", name, created);
    return created;
  }

  /**
   * Fetches the Authorization Services artifacts and optional client scopes a client currently
   * holds, so that reconciliation can determine what is missing without one lookup per artifact.
   *
   * @param clientUuid the Keycloak UUID of the client
   * @return the client's current artifact state; never {@code null}
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public @NonNull ClientArtifactState fetchClientArtifactState(final @NonNull String clientUuid) {
    final String authzBase = "/clients/" + clientUuid + "/authz/resource-server/";
    final Set<String> authzScopes = namesOf(this.fetchPaged(authzBase + "scope"));
    final Set<String> policies = namesOf(this.fetchPaged(authzBase + "policy"));
    final Set<String> permissions = namesOf(this.fetchPaged(authzBase + "permission"));

    final List<Map<String, Object>> optional = this.adminGet(
        "/clients/" + clientUuid + "/optional-client-scopes", new ParameterizedTypeReference<>() {});
    final Set<String> optionalScopeIds = new LinkedHashSet<>();
    if (optional != null) {
      for (final Map<String, Object> scope : optional) {
        final String id = getString(scope, "id");
        if (id != null) {
          optionalScopeIds.add(id);
        }
      }
    }
    return new ClientArtifactState(authzScopes, policies, permissions, optionalScopeIds);
  }

  /**
   * Returns the organizations that exist under the {@code orgs} top-level group, together with the
   * functions attached to each of them.
   *
   * @return map of organization identifier to attached function identifiers; never {@code null}
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public @NonNull Map<String, Set<String>> fetchOrgFunctionTopology() {
    final Map<String, Set<String>> topology = new LinkedHashMap<>();
    final String orgsGroupId = this.findTopLevelGroupId("orgs");

    for (final Map<String, Object> orgGroup : this.fetchGroupChildren(orgsGroupId)) {
      final String orgGroupId = getString(orgGroup, "id");
      final String orgIdentifier = getString(orgGroup, "name");
      if (orgGroupId == null || orgIdentifier == null) {
        continue;
      }
      final Set<String> functions = new LinkedHashSet<>();
      for (final Map<String, Object> child : this.fetchGroupChildren(orgGroupId)) {
        final String name = getString(child, "name");
        if (name != null && !name.startsWith("_")) {
          functions.add(name);
        }
      }
      topology.put(orgIdentifier, functions);
    }
    log.debug("Org/function topology: {} organizations", topology.size());
    return topology;
  }

  /**
   * Creates whatever the given client is missing for an org/function combination: the realm client
   * scopes, the Authorization Services scopes, the group policies, the scope permissions, and the
   * optional client scope assignments.
   *
   * <p>The operation is idempotent — artifacts already present in {@code state} are left alone.</p>
   *
   * @param clientUuid the Keycloak UUID of the client
   * @param orgIdentifier the organization identifier
   * @param functionId the function identifier
   * @param realmScopeIds map of known realm scope names to UUIDs, updated when a scope is created
   * @param state the client's current artifact state, as returned by
   *     {@link #fetchClientArtifactState(String)}
   * @return the number of artifacts created
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public int ensureFunctionArtifacts(
      final @NonNull String clientUuid,
      final @NonNull String orgIdentifier,
      final @NonNull String functionId,
      final @NonNull Map<String, String> realmScopeIds,
      final @NonNull ClientArtifactState state) {

    int created = 0;
    for (final String level : RIGHT_LEVELS) {
      final String scope = scopeName(orgIdentifier, functionId, level);
      final String scopeId = this.ensureRealmClientScope(scope, realmScopeIds);

      // KeyCloak Authorization Services maintains its own scope registry on each resource server,
      // separate from OAuth2 client scopes. Authz scopes must exist before permissions can
      // reference them.
      if (!state.authzScopeNames().contains(scope)) {
        this.createAuthzScope(clientUuid, scope);
        created++;
      }

      final String policy = policyName(orgIdentifier, functionId, level);
      String policyId = null;
      if (!state.policyNames().contains(policy)) {
        policyId = this.createGroupPolicy(clientUuid, policy,
            qualifyingGroupPaths(orgIdentifier, functionId, level));
        created++;
      }

      final String permission = permissionName(orgIdentifier, functionId, level);
      if (!state.permissionNames().contains(permission)) {
        if (policyId == null) {
          policyId = this.findAuthzArtifactId(clientUuid, "policy", policy);
        }
        if (policyId == null) {
          log.warn("Policy '{}' missing on client {} — cannot create permission '{}'",
              policy, clientUuid, permission);
        }
        else {
          this.createScopePermission(clientUuid, permission, scope, policyId);
          created++;
        }
      }

      if (!state.optionalScopeIds().contains(scopeId)) {
        this.adminPut("/clients/" + clientUuid + "/optional-client-scopes/" + scopeId);
        log.debug("Registered optional client scope '{}' on client {}", scope, clientUuid);
        created++;
      }
    }
    return created;
  }

  /**
   * Removes the artifacts a client holds for an org/function combination: the scope permissions,
   * the group policies, and the optional client scope assignments. The realm-level client scopes
   * are shared between clients and are not touched.
   *
   * <p>The operation is idempotent — artifacts that are already absent are skipped.</p>
   *
   * @param clientUuid the Keycloak UUID of the client
   * @param orgIdentifier the organization identifier
   * @param functionId the function identifier
   * @param realmScopeIds map of realm scope names to UUIDs
   * @return the number of artifacts removed
   * @throws KeycloakAdminException on any Keycloak API error
   */
  public int removeClientFunctionArtifacts(
      final @NonNull String clientUuid,
      final @NonNull String orgIdentifier,
      final @NonNull String functionId,
      final @NonNull Map<String, String> realmScopeIds) {

    int removed = 0;
    for (final String level : RIGHT_LEVELS) {
      final String permission = permissionName(orgIdentifier, functionId, level);
      final String permissionId = this.findAuthzArtifactId(clientUuid, "permission", permission);
      if (permissionId != null) {
        this.adminDelete("/clients/" + clientUuid + "/authz/resource-server/permission/" + permissionId);
        log.debug("Deleted permission '{}' on client {}", permission, clientUuid);
        removed++;
      }
      else {
        log.debug("Permission '{}' not found on client {} — skipping", permission, clientUuid);
      }

      final String policy = policyName(orgIdentifier, functionId, level);
      final String policyId = this.findAuthzArtifactId(clientUuid, "policy", policy);
      if (policyId != null) {
        this.adminDelete("/clients/" + clientUuid + "/authz/resource-server/policy/" + policyId);
        log.debug("Deleted policy '{}' on client {}", policy, clientUuid);
        removed++;
      }
      else {
        log.debug("Policy '{}' not found on client {} — skipping", policy, clientUuid);
      }

      final String scope = scopeName(orgIdentifier, functionId, level);
      final String scopeId = realmScopeIds.get(scope);
      if (scopeId == null) {
        log.debug("Client scope '{}' not found — skipping optional scope removal", scope);
        continue;
      }
      try {
        this.adminDelete("/clients/" + clientUuid + "/optional-client-scopes/" + scopeId);
        log.debug("Removed optional client scope '{}' from client {}", scope, clientUuid);
        removed++;
      }
      catch (final KeycloakAdminException e) {
        log.debug("Optional client scope '{}' not assigned to client {} — skipping: {}",
            scope, clientUuid, e.getMessage());
      }
    }
    return removed;
  }

  /**
   * Looks up an Authorization Services artifact by exact name.
   *
   * @param clientUuid the Keycloak UUID of the client
   * @param artifactType {@code policy} or {@code permission}
   * @param name the artifact name
   * @return the artifact's Keycloak UUID, or {@code null} if no such artifact exists
   * @throws KeycloakAdminException on any Keycloak API error
   */
  private @Nullable String findAuthzArtifactId(
      final @NonNull String clientUuid, final @NonNull String artifactType, final @NonNull String name) {

    final String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
    final List<Map<String, Object>> found = this.adminGet(
        "/clients/" + clientUuid + "/authz/resource-server/" + artifactType + "?name=" + encoded + "&exact=true",
        new ParameterizedTypeReference<>() {});
    return found == null || found.isEmpty() ? null : getString(found.getFirst(), "id");
  }

  /**
   * Pages through a Keycloak list endpoint and returns every entry.
   *
   * @param path relative path under the admin API base, without paging parameters
   * @return the entries; never {@code null}
   * @throws KeycloakAdminException on any Keycloak API error
   */
  private @NonNull List<Map<String, Object>> fetchPaged(final @NonNull String path) {
    final String separator = path.contains("?") ? "&" : "?";
    final List<Map<String, Object>> all = new ArrayList<>();
    int first = 0;
    while (true) {
      final List<Map<String, Object>> page = this.adminGet(
          path + separator + "first=" + first + "&max=" + PAGE_SIZE, new ParameterizedTypeReference<>() {});
      if (page == null || page.isEmpty()) {
        break;
      }
      all.addAll(page);
      if (page.size() < PAGE_SIZE) {
        break;
      }
      first += PAGE_SIZE;
    }
    return all;
  }

  /**
   * Collects the {@code name} field of each entry.
   *
   * @param entries the entries
   * @return the names; never {@code null}
   */
  private static @NonNull Set<String> namesOf(final @NonNull List<Map<String, Object>> entries) {
    final Set<String> names = new LinkedHashSet<>();
    for (final Map<String, Object> entry : entries) {
      final String name = getString(entry, "name");
      if (name != null) {
        names.add(name);
      }
    }
    return names;
  }

  /**
   * Creates an Authorization Services scope on the given client's resource server.
   *
   * <p>This is distinct from an OAuth2 client scope. KeyCloak's Authorization Services
   * maintains its own scope registry per resource server, and scope permissions must reference scopes from this
   * registry — not global client scope names or UUIDs.</p>
   *
   * @param clientUuid the Keycloak UUID of the client
   * @param scopeName the scope name (e.g. {@code org:func:read})
   * @throws KeycloakAdminException if creation fails
   */
  private void createAuthzScope(final @NonNull String clientUuid, final @NonNull String scopeName) {
    this.adminPostForId(
        "/clients/" + clientUuid + "/authz/resource-server/scope",
        Map.of("name", scopeName));
    log.debug("Created authz scope '{}' on client {}", scopeName, clientUuid);
  }

  /**
   * Creates a client scope with the given name and returns its Keycloak UUID extracted from the {@code Location}
   * header.
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
   * Creates an Authorization Services group policy on the given client.
   *
   * <p>Note: unlike the group and scope creation endpoints, the policy endpoint does not return a
   * {@code Location} header — the created policy is returned as a JSON body, and its {@code id} is
   * extracted from there.</p>
   *
   * @param clientUuid the Keycloak UUID of the client
   * @param policyName the policy name
   * @param groupPaths the group paths that grant access
   * @return the Keycloak UUID of the created policy
   * @throws KeycloakAdminException on any API error
   */
  private @NonNull String createGroupPolicy(
      final @NonNull String clientUuid,
      final @NonNull String policyName,
      final @NonNull List<String> groupPaths) {

    final List<Map<String, String>> groupEntries = groupPaths.stream()
        .map(path -> Map.of("path", path))
        .toList();
    final String policyId = this.adminPostForId(
        "/clients/" + clientUuid + "/authz/resource-server/policy/group",
        Map.of("name", policyName,
            "groups", groupEntries,
            "logic", "POSITIVE",
            "decisionStrategy", "AFFIRMATIVE"));
    log.debug("Created policy '{}' with id: {}", policyName, policyId);
    return policyId;
  }

  /**
   * Creates an Authorization Services scope permission binding a scope to a policy.
   *
   * <p>Note: the permission endpoint expects scope references by <em>name</em>, not by UUID.</p>
   *
   * @param clientUuid the Keycloak UUID of the client
   * @param permissionName the permission name
   * @param scopeName the scope name the permission applies to
   * @param policyId the Keycloak UUID of the policy to evaluate
   * @throws KeycloakAdminException on any API error
   */
  private void createScopePermission(
      final @NonNull String clientUuid,
      final @NonNull String permissionName,
      final @NonNull String scopeName,
      final @NonNull String policyId) {

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
  // Rights holders
  // ---------------------------------------------------------------------------

  /**
   * Fetches all users holding a right on the given (organization, function) combination.
   *
   * <p>Queries six candidate groups:</p>
   * <ul>
   *   <li>{@code /orgs/{org}/_admin}, {@code /_write}, {@code /_read} — org-wide rights</li>
   *   <li>{@code /orgs/{org}/{func}/_admin}, {@code /_write}, {@code /_read} — function-level rights</li>
   * </ul>
   *
   * <p>Each user appears exactly once in the result with the highest effective right
   * ({@code admin} &gt; {@code write} &gt; {@code read}). When the same level is held both
   * org-wide and function-specific, the function scope wins (more specific).</p>
   *
   * <p>Results are sorted by right descending (admin → write → read), then by name ascending
   * within each right (nulls last).</p>
   *
   * @param orgIdentifier the organization identifier
   * @param functionId the function identifier
   * @return list of rights holders; never {@code null}
   * @throws KeycloakAdminException if the org group or function sub-group is not found
   */
  public @NonNull List<RightsHolderEntry> fetchFunctionRightsHolders(
      final @NonNull String orgIdentifier,
      final @NonNull String functionId) {

    final String orgGroupId = this.resolveOrgGroupId(orgIdentifier);
    if (orgGroupId == null) {
      throw new KeycloakAdminException("Org group not found: " + orgIdentifier);
    }

    final List<Map<String, Object>> orgChildren = this.fetchGroupChildren(orgGroupId);

    // Resolve org-level right group IDs (_admin, _write, _read)
    final String orgAdminGroupId = findChildGroupId(orgChildren, "_admin");
    final String orgWriteGroupId = findChildGroupId(orgChildren, "_write");
    final String orgReadGroupId = findChildGroupId(orgChildren, "_read");

    // Resolve function sub-group
    final String funcGroupId = orgChildren.stream()
        .filter(c -> functionId.equals(getString(c, "name")))
        .map(c -> getString(c, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new KeycloakAdminException(
            "Function group '" + functionId + "' not found under org '" + orgIdentifier + "'"));

    final List<Map<String, Object>> funcChildren = this.fetchGroupChildren(funcGroupId);
    final String funcAdminGroupId = findChildGroupId(funcChildren, "_admin");
    final String funcWriteGroupId = findChildGroupId(funcChildren, "_write");
    final String funcReadGroupId = findChildGroupId(funcChildren, "_read");

    // Fetch members from all six groups
    // Structure: Map<userId, best (rightLevel, scope, UserInfo)>
    final Map<String, RightsHolderAccumulator> accum = new LinkedHashMap<>();

    collectMembers(accum, orgAdminGroupId, 3, "organization");
    collectMembers(accum, orgWriteGroupId, 2, "organization");
    collectMembers(accum, orgReadGroupId, 1, "organization");
    collectMembers(accum, funcAdminGroupId, 3, "function");
    collectMembers(accum, funcWriteGroupId, 2, "function");
    collectMembers(accum, funcReadGroupId, 1, "function");

    // Build result sorted by right desc, then name asc (nulls last)
    return accum.values().stream()
        .map(a -> new RightsHolderEntry(
            a.userId, a.personalIdentityNumber, a.name, levelToString(a.level), a.scope))
        .sorted((a, b) -> {
          final int rightCmp = Integer.compare(levelFromString(b.right()), levelFromString(a.right()));
          if (rightCmp != 0) {
            return rightCmp;
          }
          if (a.name() == null && b.name() == null) {
            return 0;
          }
          if (a.name() == null) {
            return 1;
          }
          if (b.name() == null) {
            return -1;
          }
          return a.name().compareToIgnoreCase(b.name());
        })
        .toList();
  }

  private void collectMembers(
      final @NonNull Map<String, RightsHolderAccumulator> accum,
      final @Nullable String groupId,
      final int level,
      final @NonNull String scope) {

    if (groupId == null) {
      return;
    }
    final List<UserInfo> members = this.fetchGroupMembers(groupId);
    for (final UserInfo user : members) {
      final RightsHolderAccumulator existing = accum.get(user.userId());
      if (existing == null) {
        accum.put(user.userId(), new RightsHolderAccumulator(
            user.userId(), user.personalIdentityNumber(), buildDisplayName(user), level, scope));
      }
      else if (level > existing.level
          || (level == existing.level && "function".equals(scope) && "organization".equals(existing.scope))) {
        existing.level = level;
        existing.scope = scope;
      }
    }
  }

  private static @Nullable String buildDisplayName(final @NonNull UserInfo user) {
    final String first = user.firstName();
    final String last = user.lastName();
    if (first != null || last != null) {
      final String full = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
      if (!full.isEmpty()) {
        return full;
      }
    }
    if (user.username() != null && !user.username().isBlank()) {
      return user.username();
    }
    return null;
  }

  private static @Nullable String findChildGroupId(
      final @NonNull List<Map<String, Object>> children,
      final @NonNull String name) {
    return children.stream()
        .filter(c -> name.equals(getString(c, "name")))
        .map(c -> getString(c, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private static @NonNull String levelToString(final int level) {
    return switch (level) {
      case 3 -> "admin";
      case 2 -> "write";
      default -> "read";
    };
  }

  private static int levelFromString(final @NonNull String right) {
    return switch (right) {
      case "admin" -> 3;
      case "write" -> 2;
      default -> 1;
    };
  }

  private static final class RightsHolderAccumulator {
    final String userId;
    final String personalIdentityNumber;
    final String name;
    int level;
    String scope;

    RightsHolderAccumulator(final String userId, final String personalIdentityNumber,
        final String name, final int level, final String scope) {
      this.userId = userId;
      this.personalIdentityNumber = personalIdentityNumber;
      this.name = name;
      this.level = level;
      this.scope = scope;
    }
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
   * If there is no space the whole string is used as {@code firstName}. A random UUID is supplied as {@code username}
   * because Keycloak 26 requires it.</p>
   *
   * @param name display name (split into first / last name)
   * @param email optional email address
   * @param personalIdentityNumber 12-digit personal identity number
   * @param phoneNumber optional phone number
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
    final String lastName = spaceIdx > 0 ? name.substring(spaceIdx + 1) : "";

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
   * @param userId the Keycloak user UUID
   * @param right the right level ({@code read}, {@code write}, or {@code admin})
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
        }
        catch (final KeycloakAdminException e) {
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
   * @param functionId the function identifier
   * @param userId the Keycloak user UUID
   * @param right the right level ({@code read}, {@code write}, or {@code admin})
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
        }
        catch (final KeycloakAdminException e) {
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
   * @param userId the Keycloak user UUID
   * @param right the right level ({@code read}, {@code write}, or {@code admin})
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
   * @param functionId the function identifier
   * @param userId the Keycloak user UUID
   * @param right the right level ({@code read}, {@code write}, or {@code admin})
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
   * Sets {@code email} at the top level. Stores {@code phoneNumber} in the {@code phoneNumber} custom attribute
   * (replaces previous value, or removes the attribute if {@code null} or blank).</p>
   *
   * @param userId the Keycloak user UUID
   * @param name the user's full name
   * @param email optional email address
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
    final String lastName = spaceIdx > 0 ? name.substring(spaceIdx + 1) : "";

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
    }
    else {
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
   * means "do not change". An empty string for {@code contactEmail} or {@code contactPhone} means "clear the
   * attribute".</p>
   *
   * @param orgIdentifier the organization identifier
   * @param nameSv new Swedish name, or {@code null} to leave unchanged
   * @param nameEn new English name, or {@code null} to leave unchanged
   * @param contactEmail new contact email, or {@code null} to leave unchanged, or {@code ""} to clear
   * @param contactPhone new contact phone, or {@code null} to leave unchanged, or {@code ""} to clear
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
        }
        else {
          attrs.put("contact_info", List.of(toContactInfoJson(contactInfo)));
        }
      }
      else {
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
        }
        else {
          attrs.put("contact_info", List.of(toContactInfoJson(contactInfo)));
        }
      }
      else {
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
   * this method, and for cleaning up any client scopes / authz policies that were created when functions were attached.
   * For now this method performs a simple group DELETE.</p>
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
   * Detaches a function from an organization by removing the function sub-group and all associated Keycloak artifacts
   * created during attachment.
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
   * @param functionId the function identifier
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

    // Resolve the managed clients that handle this function, and the realm client scopes needed
    // to unassign the optional client scopes
    final List<ManagedClientInfo> clients = this.resolveManagedClientsForFunction(functionId);
    final Map<String, String> realmScopeIds = this.fetchRealmClientScopeIds();

    // Remove the permissions, policies and optional client scope assignments per client
    for (final ManagedClientInfo client : clients) {
      final int removed = this.removeClientFunctionArtifacts(
          client.uuid(), orgIdentifier, functionId, realmScopeIds);
      log.debug("Removed {} artifacts from client '{}' for '{}:{}'",
          removed, client.clientId(), orgIdentifier, functionId);
    }

    // Delete the realm-level client scopes, which are shared between clients
    for (final String level : RIGHT_LEVELS) {
      final String scope = scopeName(orgIdentifier, functionId, level);
      final String scopeId = realmScopeIds.get(scope);
      if (scopeId == null) {
        log.warn("Client scope '{}' not found during detach cleanup — skipping", scope);
        continue;
      }
      this.adminDelete("/client-scopes/" + scopeId);
      log.debug("Deleted realm client scope '{}'", scope);
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
   * Fetches a single page of users from Keycloak.
   *
   * <p>Returns basic user profile only — rights are not included. Use
   * {@link #fetchUserRights(String)} to fetch rights for a specific user.</p>
   *
   * @param first 0-based offset
   * @param max maximum number of results to return
   * @return list of users for the requested page; never {@code null}
   */
  public @NonNull List<UserInfo> fetchUsersPaged(final int first, final int max) {
    final Set<String> superuserIds = this.fetchSuperuserIds();
    final List<Map<String, Object>> page = this.adminGet(
        "/users?first=" + first + "&max=" + max,
        new ParameterizedTypeReference<>() {});
    if (page == null) {
      return List.of();
    }
    return page.stream().map(u -> this.mapUser(u, superuserIds)).toList();
  }

  /**
   * Returns the total number of users in the Keycloak realm.
   *
   * @return total user count
   */
  public int fetchUserCount() {
    final Integer count = this.adminGet("/users/count", new ParameterizedTypeReference<Integer>() {});
    return count != null ? count : 0;
  }

  /**
   * Fetches the rights for a specific user by inspecting their Keycloak group memberships.
   *
   * <p>Group paths are parsed to extract organizational rights:</p>
   * <ul>
   *   <li>{@code /orgs/{orgId}/_admin|_write|_read} → org-level right</li>
   *   <li>{@code /orgs/{orgId}/{funcId}/_admin|_write|_read} → function-level right</li>
   * </ul>
   * <p>Groups outside the {@code /orgs/} hierarchy are ignored.</p>
   *
   * @param userId the Keycloak user UUID
   * @return list of rights; never {@code null}
   */
  public @NonNull List<UserRight> fetchUserRights(final @NonNull String userId) {
    final List<Map<String, Object>> groups = this.adminGet(
        "/users/" + userId + "/groups",
        new ParameterizedTypeReference<>() {});
    if (groups == null || groups.isEmpty()) {
      return List.of();
    }
    final List<UserRight> rights = new ArrayList<>();
    for (final Map<String, Object> group : groups) {
      final String path = getString(group, "path");
      if (path == null) {
        continue;
      }
      final String[] parts = path.split("/");
      // Org-level: ["", "orgs", "{orgId}", "_admin|_write|_read"]
      if (parts.length == 4 && "orgs".equals(parts[1]) && isRightGroupName(parts[3])) {
        rights.add(new UserRight(parts[2], null, rightFromName(parts[3])));
      }
      // Function-level: ["", "orgs", "{orgId}", "{funcId}", "_admin|_write|_read"]
      else if (parts.length == 5 && "orgs".equals(parts[1]) && isRightGroupName(parts[4])) {
        rights.add(new UserRight(parts[2], parts[3], rightFromName(parts[4])));
      }
    }
    return List.copyOf(rights);
  }

  /**
   * Fetches members of the given KeyCloak group.
   *
   * @param groupId KeyCloak group UUID
   * @return list of group members; never {@code null}
   */
  public @NonNull List<UserInfo> fetchGroupMembers(final @NonNull String groupId) {
    final List<Map<String, Object>> allMembers = new ArrayList<>();
    int first = 0;
    while (true) {
      final List<Map<String, Object>> page = this.adminGet(
          "/groups/" + groupId + "/members?first=" + first + "&max=" + PAGE_SIZE,
          new ParameterizedTypeReference<>() {});
      if (page == null || page.isEmpty()) {
        break;
      }
      allMembers.addAll(page);
      if (page.size() < PAGE_SIZE) {
        break;
      }
      first += PAGE_SIZE;
    }
    return allMembers.stream().map(u -> this.mapUser(u, Set.of())).toList();
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
    final List<Map<String, Object>> result = new ArrayList<>();
    int first = 0;
    while (true) {
      final List<Map<String, Object>> page = this.adminGet(
          "/groups/" + groupId + "/children?first=" + first + "&max=" + PAGE_SIZE,
          new ParameterizedTypeReference<>() {});
      if (page == null || page.isEmpty()) {
        break;
      }
      result.addAll(page);
      if (page.size() < PAGE_SIZE) {
        break;
      }
      first += PAGE_SIZE;
    }
    return result;
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
  // Live admin-check helpers (called per-request, not cached in session)
  // ---------------------------------------------------------------------------

  /**
   * Returns the sorted list of unique user IDs that hold any right in any of the given organizations. Used to determine
   * the pageable user set for a non-superuser admin.
   *
   * <p>Traverses org-level right groups and all function sub-groups' right groups.</p>
   *
   * @param orgIdentifiers set of organization identifiers to traverse
   * @return sorted list of user IDs; never {@code null}
   */
  public @NonNull List<String> fetchUserIdsForOrgs(final @NonNull Collection<String> orgIdentifiers) {
    final LinkedHashSet<String> userIds = new LinkedHashSet<>();
    for (final String orgIdentifier : orgIdentifiers) {
      final Optional<OrganizationInfo> orgOpt = this.fetchOrganizationByIdentifier(orgIdentifier);
      if (orgOpt.isEmpty()) {
        continue;
      }
      final List<Map<String, Object>> orgChildren = this.fetchGroupChildren(orgOpt.get().groupId());
      for (final Map<String, Object> child : orgChildren) {
        final String name = getString(child, "name");
        final String id = getString(child, "id");
        if (id == null) {
          continue;
        }
        if ("_admin".equals(name) || "_write".equals(name) || "_read".equals(name)) {
          this.fetchGroupMembers(id).forEach(u -> userIds.add(u.userId()));
        }
        else {
          // Function sub-group — traverse its right-groups
          for (final Map<String, Object> funcChild : this.fetchGroupChildren(id)) {
            final String fcName = getString(funcChild, "name");
            final String fcId = getString(funcChild, "id");
            if (fcId != null && ("_admin".equals(fcName) || "_write".equals(fcName) || "_read".equals(fcName))) {
              this.fetchGroupMembers(fcId).forEach(u -> userIds.add(u.userId()));
            }
          }
        }
      }
    }
    return userIds.stream().sorted().toList();
  }

  /**
   * Returns {@code true} if at least one other user (not {@code excludeUserId}) holds the org-level admin right for the
   * given organization.
   *
   * <p>Returns {@code true} (non-blocking) when the org cannot be found.</p>
   *
   * @param orgIdentifier the organization identifier
   * @param excludeUserId the Keycloak user UUID to exclude from the check
   * @return {@code true} if another admin exists; {@code false} if the excluded user is the last admin
   */
  public boolean hasOtherOrgAdmin(final @NonNull String orgIdentifier, final @NonNull String excludeUserId) {
    final Optional<OrganizationInfo> orgOpt = this.fetchOrganizationByIdentifier(orgIdentifier);
    if (orgOpt.isEmpty()) {
      return true; // conservative: can't determine → allow
    }
    final String adminGroupId = this.fetchGroupChildren(orgOpt.get().groupId()).stream()
        .filter(c -> "_admin".equals(getString(c, "name")))
        .map(c -> getString(c, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
    if (adminGroupId == null) {
      return false;
    }
    return this.fetchGroupMembers(adminGroupId).stream()
        .anyMatch(u -> !u.userId().equals(excludeUserId));
  }

  /**
   * Returns {@code true} if at least one other user (not {@code excludeUserId}) holds admin rights for the given
   * function within the given organization.
   *
   * <p>Both org-level admin members and function-level admin members are considered,
   * since org-level admins implicitly cover all functions.</p>
   *
   * @param orgIdentifier the organization identifier
   * @param functionId the function identifier
   * @param excludeUserId the Keycloak user UUID to exclude from the check
   * @return {@code true} if another admin exists; {@code false} if the excluded user is the last admin
   */
  public boolean hasOtherFunctionAdmin(
      final @NonNull String orgIdentifier,
      final @NonNull String functionId,
      final @NonNull String excludeUserId) {

    final Optional<OrganizationInfo> orgOpt = this.fetchOrganizationByIdentifier(orgIdentifier);
    if (orgOpt.isEmpty()) {
      return true;
    }
    final List<Map<String, Object>> orgChildren = this.fetchGroupChildren(orgOpt.get().groupId());

    // Org-level admin covers all functions
    final String orgAdminGroupId = orgChildren.stream()
        .filter(c -> "_admin".equals(getString(c, "name")))
        .map(c -> getString(c, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
    if (orgAdminGroupId != null && this.fetchGroupMembers(orgAdminGroupId).stream()
        .anyMatch(u -> !u.userId().equals(excludeUserId))) {
      return true;
    }

    // Function-level admin
    final String funcGroupId = orgChildren.stream()
        .filter(c -> functionId.equals(getString(c, "name")))
        .map(c -> getString(c, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
    if (funcGroupId == null) {
      return false;
    }
    final String funcAdminGroupId = this.fetchGroupChildren(funcGroupId).stream()
        .filter(c -> "_admin".equals(getString(c, "name")))
        .map(c -> getString(c, "id"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
    if (funcAdminGroupId == null) {
      return false;
    }
    return this.fetchGroupMembers(funcAdminGroupId).stream()
        .anyMatch(u -> !u.userId().equals(excludeUserId));
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

  private static boolean isRightGroupName(final @NonNull String name) {
    return "_admin".equals(name) || "_write".equals(name) || "_read".equals(name);
  }

  private static @NonNull String rightFromName(final @NonNull String name) {
    return switch (name) {
      case "_admin" -> "admin";
      case "_write" -> "write";
      default -> "read";
    };
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
   * Parses the {@code contact_info} group attribute value (a JSON string) into a mutable map. Returns an empty map if
   * the value is null, blank, or not valid JSON.
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
    }
    catch (final Exception e) {
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
