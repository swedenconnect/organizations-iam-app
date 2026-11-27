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
package se.swedenconnect.iam.admin.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import se.swedenconnect.iam.admin.auth.LoginRejection;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.FunctionInfo;
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.iam.commons.types.OrganizationID;
import se.swedenconnect.iam.security.claims.FunctionScopedAuthority;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;
import se.swedenconnect.iam.security.claims.OrganizationRight;
import se.swedenconnect.iam.security.claims.OrganizationalAuthority;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Everything put in the HTTP session has to survive JDK serialization, because that is how Spring
 * Session writes a session to Redis.
 *
 * <p>The in-memory store keeps the attributes as live objects and never serializes them, so a type
 * that cannot be serialized breaks nothing until Redis is switched on — in a deployment, not here.
 * These tests are what notices instead. A new session attribute belongs in one of them, or, when it
 * is not reachable from this package, in a test of its own next to the type;
 * {@code PendingImportBatchSerializationTest} covers the pending import batch.</p>
 *
 * @author Per Fredrik Plars
 */
class SessionAttributeSerializationTest {

  private static final String SUBJECT = "f1b0a3c4-5d6e-4f70-8192-a3b4c5d6e7f8";

  @Test
  void theSessionDataBootstrappedAtLoginSurvives() {
    final AdminSessionData data = new AdminSessionData(false, "walletreg", "2021006883",
        List.of(new FunctionInfo("walletreg",
            new LocalizedString(Map.of("sv", "Plånboksregistrering", "en", "Wallet registration")),
            new LocalizedString(Map.of("sv", "Registrering av plånböcker")))),
        Set.of("2021006883"), claim());

    final AdminSessionData restored = roundTrip(data);

    assertThat(restored.currentUserIsSuperuser()).isFalse();
    assertThat(restored.functionConstraint()).isEqualTo("walletreg");
    assertThat(restored.orgConstraint()).isEqualTo("2021006883");
    assertThat(restored.adminOrgIdentifiers()).containsExactly("2021006883");
    assertThat(restored.functions()).hasSize(1);
    assertThat(restored.functions().getFirst().name().get("sv"))
        .isEqualTo("Plånboksregistrering");
    assertThat(restored.claim().orgEntries()).hasSize(1);
    assertThat(restored.claim().orgEntries().getFirst().functions())
        .containsExactly(new OrgRightsClaim.FunctionEntry("walletreg", "admin"));
  }

  @Test
  void theRejectionOfALoginSurvives() {
    final LoginRejection rejection =
        new LoginRejection(LoginRejection.NO_ADMIN_RIGHT, List.of("2021006883"));

    assertThat(roundTrip(rejection)).isEqualTo(rejection);
  }

  @Test
  void theAuthenticatedUserAndItsAuthoritiesSurvive() {
    final List<GrantedAuthority> authorities = List.of(
        OrganizationalAuthority.of(OrganizationID.of("2021006883"), "walletreg",
            OrganizationRight.ADMIN),
        FunctionScopedAuthority.of(OrganizationID.of("2021006883"), OrganizationRight.WRITE));
    final Instant issuedAt = Instant.now();
    final OidcIdToken idToken = new OidcIdToken("id-token", issuedAt, issuedAt.plusSeconds(300),
        Map.of("sub", SUBJECT, "name", "Klara Karlsson"));
    final Authentication authentication = new OAuth2AuthenticationToken(
        new DefaultOidcUser(authorities, idToken), authorities, "iam-admin");

    final SecurityContextImpl restored = roundTrip(new SecurityContextImpl(authentication));

    assertThat(restored.getAuthentication().getName()).isEqualTo(SUBJECT);
    assertThat(restored.getAuthentication().getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactlyInAnyOrder("2021006883:walletreg:admin", "2021006883:write");
  }

  @Test
  void theAuthorizedClientOfTheLoginSurvives() {
    final OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(registration(), SUBJECT,
        new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-token-value",
            Instant.parse("2026-11-24T10:00:00Z"), Instant.parse("2026-11-24T10:05:00Z"),
            Set.of("openid", "profile")),
        new OAuth2RefreshToken("refresh-token-value", Instant.parse("2026-11-24T10:00:00Z")));

    final Map<String, OAuth2AuthorizedClient> restored =
        roundTrip(new LinkedHashMap<>(Map.of("iam-admin", client)));

    assertThat(restored).containsOnlyKeys("iam-admin");
    assertThat(restored.get("iam-admin").getPrincipalName()).isEqualTo(SUBJECT);
    assertThat(restored.get("iam-admin").getAccessToken().getTokenValue())
        .isEqualTo("access-token-value");
    assertThat(restored.get("iam-admin").getRefreshToken()).isNotNull();
  }

  @Test
  void thePendingAuthorizationRequestSurvives() {
    final OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
        .authorizationUri("https://localhost/authorize")
        .clientId("iam-admin")
        .redirectUri("https://localhost/login/oauth2/code/iam-admin")
        .scopes(Set.of("openid", "profile"))
        .state("state-value")
        .build();

    assertThat(roundTrip(request).getState()).isEqualTo("state-value");
  }

  private static OrgRightsClaim claim() {
    return new OrgRightsClaim(false, List.of(new OrgRightsClaim.OrgEntry(
        OrganizationID.of("2021006883"), "Myndigheten för digital förvaltning",
        new LocalizedString(Map.of("sv", "DIGG")), "admin",
        List.of(new OrgRightsClaim.FunctionEntry("walletreg", "admin")))));
  }

  private static ClientRegistration registration() {
    return ClientRegistration.withRegistrationId("iam-admin")
        .clientId("iam-admin")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("https://localhost/login/oauth2/code/iam-admin")
        .authorizationUri("https://localhost/authorize")
        .tokenUri("https://localhost/token")
        .scope("openid", "profile")
        .build();
  }

  /** Writes the object out with JDK serialization and reads it back, as Redis would. */
  @SuppressWarnings("unchecked")
  private static <T> T roundTrip(final T object) {
    try {
      final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
        out.writeObject(object);
      }
      try (ObjectInputStream in =
          new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
        return (T) in.readObject();
      }
    }
    catch (final Exception e) {
      throw new AssertionError(
          "%s does not survive the serialization Spring Session uses for Redis"
              .formatted(object.getClass().getName()), e);
    }
  }

}
