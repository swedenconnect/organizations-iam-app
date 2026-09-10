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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import se.swedenconnect.iam.admin.config.IamAdminProperties;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests what {@link KeycloakAdminClient#createUser} writes to Keycloak. The client reads no
 * configuration, so every variation follows from the arguments alone.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateUserRequestBodyTest {

  private static final String ADMIN_API_BASE = "http://localhost:8080/admin/realms/orgiam";

  private static final String USERS_URI = ADMIN_API_BASE + "/users";

  private static final String CREATED_ID = "d1f0c0de-0000-4000-8000-000000000001";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock
  private OAuth2AuthorizedClientManager authorizedClientManager;

  private RestClient.Builder restClientBuilder;

  private MockRestServiceServer server;

  @BeforeEach
  void setUp() {
    final OAuth2AuthorizedClient authorizedClient = mock(OAuth2AuthorizedClient.class);
    final OAuth2AccessToken accessToken = new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER, "fake-token", Instant.now(), Instant.now().plusSeconds(300));
    lenient().when(authorizedClient.getAccessToken()).thenReturn(accessToken);
    lenient().when(this.authorizedClientManager.authorize(any())).thenReturn(authorizedClient);

    this.restClientBuilder = RestClient.builder();
    this.server = MockRestServiceServer.bindTo(this.restClientBuilder).build();
  }

  /** With no user ID given, Keycloak receives a random UUID as username. */
  @Test
  void noUserId_writesRandomUuidUsername() {
    final JsonNode body = this.create(
        () -> this.newClient().createUser(null, "Martin Lindström", null, "196911292032", null, null, null));

    final String username = body.path("username").asText();
    assertThatCode(() -> UUID.fromString(username)).doesNotThrowAnyException();
    assertThat(body.path("firstName").asText()).isEqualTo("Martin");
    assertThat(body.path("lastName").asText()).isEqualTo("Lindström");
    assertThat(body.path("attributes").path("personalIdentityNumber").get(0).asText())
        .isEqualTo("196911292032");
    assertThat(body.has("credentials")).isFalse();
  }

  /** A supplied user ID becomes the username verbatim. */
  @Test
  void userId_becomesUsername() {
    final JsonNode body = this.create(
        () -> this.newClient().createUser("martin", "Martin Lindström", null, "196911292032", null, null, null));

    assertThat(body.path("username").asText()).isEqualTo("martin");
  }

  /** A blank user ID is treated as absent. */
  @Test
  void blankUserId_writesRandomUuidUsername() {
    final JsonNode body = this.create(
        () -> this.newClient().createUser("  ", "Martin Lindström", null, "196911292032", null, null, null));

    assertThatCode(() -> UUID.fromString(body.path("username").asText())).doesNotThrowAnyException();
  }

  /**
   * The organizational affiliation is written to a user attribute of the same name, and brings the
   * organization number with it. The lookup finds an organization group carrying a legal name, so
   * {@code orgName} is written too. {@code orgUnit} never is.
   */
  @Test
  void orgAffiliation_isWrittenWithOrgNumberAndOrgName() {
    this.expectOrganizationLookup("2021006883", "Myndigheten för Digital förvaltning");

    final JsonNode body = this.create(() -> this.newClient().createUser(
        null, "Martin Lindström", "martin@example.com", null, "martin@2021006883", "+46701234567", null));

    final JsonNode attributes = body.path("attributes");
    assertThat(attributes.path("orgAffiliation").get(0).asText()).isEqualTo("martin@2021006883");
    assertThat(attributes.path("orgNumber").get(0).asText()).isEqualTo("2021006883");
    assertThat(attributes.path("orgName").get(0).asText())
        .isEqualTo("Myndigheten för Digital förvaltning");
    assertThat(attributes.has("orgUnit")).isFalse();
    assertThat(attributes.path("phoneNumber").get(0).asText()).isEqualTo("+46701234567");
    assertThat(attributes.has("personalIdentityNumber")).isFalse();
    assertThat(body.path("email").asText()).isEqualTo("martin@example.com");
  }

  /**
   * An affiliation naming an organization that does not exist still yields the organization number.
   * The user is created without an {@code orgName} rather than with a guessed one.
   */
  @Test
  void orgAffiliation_unknownOrganization_writesOrgNumberOnly() {
    this.expectNoOrganization("5561234567");

    final JsonNode body = this.create(() -> this.newClient().createUser(
        null, "Martin Lindström", null, null, "martin@5561234567", null, null));

    final JsonNode attributes = body.path("attributes");
    assertThat(attributes.path("orgAffiliation").get(0).asText()).isEqualTo("martin@5561234567");
    assertThat(attributes.path("orgNumber").get(0).asText()).isEqualTo("5561234567");
    assertThat(attributes.has("orgName")).isFalse();
  }

  /** A Keycloak error while resolving the organization does not fail the creation. */
  @Test
  void orgAffiliation_organizationLookupFails_writesOrgNumberOnly() {
    this.server.expect(requestTo(orgLookupUri("2021006883")))
        .andRespond(withServerError());

    final JsonNode body = this.create(() -> this.newClient().createUser(
        null, "Martin Lindström", null, null, "martin@2021006883", null, null));

    final JsonNode attributes = body.path("attributes");
    assertThat(attributes.path("orgNumber").get(0).asText()).isEqualTo("2021006883");
    assertThat(attributes.has("orgName")).isFalse();
  }

  /** An organization group carrying no legal name yields no {@code orgName} either. */
  @Test
  void orgAffiliation_organizationWithoutLegalName_writesOrgNumberOnly() {
    this.server.expect(requestTo(orgLookupUri("2021006883")))
        .andRespond(withSuccess(
            "[ { \"id\": \"org-group-id\", \"name\": \"2021006883\", \"path\": \"/orgs/2021006883\","
                + " \"attributes\": {} } ]",
            MediaType.APPLICATION_JSON));

    this.expectOrgGroupChildren();

    final JsonNode body = this.create(() -> this.newClient().createUser(
        null, "Martin Lindström", null, null, "martin@2021006883", null, null));

    assertThat(body.path("attributes").has("orgName")).isFalse();
  }

  /**
   * Every managed client is given the organizational identity scope alongside the personal identity
   * number and phone scopes, so a client can ask for the claims the affiliation produces.
   */
  @Test
  void baseOptionalScopes_carryTheOrganizationalIdentityScope() {
    assertThat(KeycloakAdminClient.BASE_OPTIONAL_SCOPES).containsExactly(
        "https://id.oidc.se/scope/naturalPersonNumber",
        "https://id.oidc.se/scope/naturalPersonOrgId",
        "phone");
  }

  /** The organization number is what follows the '@', whatever the local part looks like. */
  @Test
  void orgNumberOf_takesThePartAfterTheAt() {
    assertThat(KeycloakAdminClient.orgNumberOf("martin@2021006883")).isEqualTo("2021006883");
    assertThat(KeycloakAdminClient.orgNumberOf("martin.lindström@5561234567")).isEqualTo("5561234567");
    assertThat(KeycloakAdminClient.orgNumberOf("2021006883")).isNull();
    assertThat(KeycloakAdminClient.orgNumberOf("martin@")).isNull();
  }

  /** A temporary password is written as a password credential marked temporary. */
  @Test
  void temporaryPassword_isWrittenAsTemporaryCredential() {
    final JsonNode body = this.create(() -> this.newClient().createUser(
        "martin", "Martin Lindström", null, "196911292032", null, null, "initial-secret"));

    final JsonNode credential = body.path("credentials").get(0);
    assertThat(credential.path("type").asText()).isEqualTo("password");
    assertThat(credential.path("value").asText()).isEqualTo("initial-secret");
    assertThat(credential.path("temporary").asBoolean()).isTrue();
  }

  /** A user carrying no eID attribute at all is written with an empty attributes map. */
  @Test
  void noEidAttributes_writesEmptyAttributes() {
    final JsonNode body = this.create(
        () -> this.newClient().createUser("martin", "Martin", null, null, null, null, null));

    assertThat(body.path("attributes")).isEmpty();
    assertThat(body.has("lastName")).isFalse();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private JsonNode create(final Runnable call) {
    final AtomicReference<String> captured = new AtomicReference<>();
    this.server.expect(requestTo(USERS_URI))
        .andExpect(method(HttpMethod.POST))
        .andExpect(request -> captured.set(((MockClientHttpRequest) request).getBodyAsString()))
        .andRespond(withCreatedEntity(URI.create(ADMIN_API_BASE + "/users/" + CREATED_ID)));

    call.run();
    this.server.verify();

    try {
      return MAPPER.readTree(captured.get());
    }
    catch (final Exception e) {
      throw new IllegalStateException("Could not read the captured request body", e);
    }
  }

  /** The organization group lookup the {@code orgName} resolution performs. */
  private static String orgLookupUri(final String orgIdentifier) {
    return ADMIN_API_BASE + "/groups?search=" + orgIdentifier + "&exact=true&briefRepresentation=false";
  }

  /**
   * Answers the organization group lookup with a group carrying the given legal name, and the
   * attached-function listing that mapping the group performs next.
   */
  private void expectOrganizationLookup(final String orgIdentifier, final String legalName) {
    this.server.expect(requestTo(orgLookupUri(orgIdentifier)))
        .andRespond(withSuccess(
            "[ { \"id\": \"org-group-id\", \"name\": \"" + orgIdentifier + "\", \"path\": \"/orgs/"
                + orgIdentifier + "\", \"attributes\": { \"organization_name\": [ \"" + legalName
                + "\" ] } } ]",
            MediaType.APPLICATION_JSON));
    this.expectOrgGroupChildren();
  }

  /** Answers the listing of an organization group's children with an empty list. */
  private void expectOrgGroupChildren() {
    this.server.expect(requestTo(ADMIN_API_BASE + "/groups/org-group-id/children?first=0&max=500"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
  }

  /** Answers the organization group lookup with nothing found. */
  private void expectNoOrganization(final String orgIdentifier) {
    this.server.expect(requestTo(orgLookupUri(orgIdentifier)))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
  }

  private KeycloakAdminClient newClient() {
    final IamAdminProperties props = new IamAdminProperties();
    props.setAdminApiBase(ADMIN_API_BASE);
    return new KeycloakAdminClient(this.authorizedClientManager, this.restClientBuilder, props);
  }

}
