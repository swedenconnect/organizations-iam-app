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
package se.swedenconnect.iam.security.client;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthenticatedPrincipalOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OrgKeyedAuthorizedClientRepository}.
 *
 * @author Martin Lindström
 */
class OrgKeyedAuthorizedClientRepositoryTest {

  private static final String API = "api";
  private static final String ORG_ONLY = "org-only";
  private static final String LOGIN = "login";

  private static final ClientRegistration API_REGISTRATION = registration(API, "{org}:{function}:read");
  private static final ClientRegistration ORG_ONLY_REGISTRATION = registration(ORG_ONLY, "{org}:demo:read");
  private static final ClientRegistration LOGIN_REGISTRATION = registration(LOGIN, "openid");

  private final InMemoryClientRegistrationRepository registrations =
      new InMemoryClientRegistrationRepository(API_REGISTRATION, ORG_ONLY_REGISTRATION, LOGIN_REGISTRATION);

  private final OAuthClientContext context = new OAuthClientContext();

  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  private final Authentication alice = user("alice");

  private OrgKeyedAuthorizedClientRepository repository(final String defaultFunction) {
    return new OrgKeyedAuthorizedClientRepository(this.registrations, this.context, defaultFunction);
  }

  private OrgKeyedAuthorizedClientRepository repository() {
    return this.repository(null);
  }

  // Shows the problem being solved: Spring Boot's default repository returns the token obtained for
  // organization A when organization B is current.
  @Test
  void bootDefaultRepository_returnsTokenForOtherOrganization() {
    final OAuth2AuthorizedClientRepository bootDefault = new AuthenticatedPrincipalOAuth2AuthorizedClientRepository(
        new InMemoryOAuth2AuthorizedClientService(this.registrations));
    this.context.setOrg("A");
    this.context.setFunction("demo");
    final OAuth2AuthorizedClient tokenForA = client(API_REGISTRATION, "alice", "A:demo:read");
    bootDefault.saveAuthorizedClient(tokenForA, this.alice, this.request, this.response);

    this.context.setOrg("B");

    final OAuth2AuthorizedClient loaded = bootDefault.loadAuthorizedClient(API, this.alice, this.request);
    assertThat(loaded).isNotNull();
    assertThat(loaded.getAccessToken().getTokenValue()).isEqualTo(tokenForA.getAccessToken().getTokenValue());
  }

  @Test
  void switchingOrganization_doesNotReturnOtherOrganizationsToken() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    this.context.setOrg("A");
    this.context.setFunction("demo");
    final OAuth2AuthorizedClient tokenForA = client(API_REGISTRATION, "alice", "A:demo:read");
    repository.saveAuthorizedClient(tokenForA, this.alice, this.request, this.response);

    this.context.setOrg("B");
    assertThat(this.load(repository, API)).isNull();

    this.context.setOrg("A");
    assertThat(this.load(repository, API)).isSameAs(tokenForA);
  }

  @Test
  void tokensForSeveralOrganizations_areKeptSeparately() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    this.context.setFunction("demo");
    this.context.setOrg("A");
    final OAuth2AuthorizedClient tokenForA = client(API_REGISTRATION, "alice", "A:demo:read");
    repository.saveAuthorizedClient(tokenForA, this.alice, this.request, this.response);
    this.context.setOrg("B");
    final OAuth2AuthorizedClient tokenForB = client(API_REGISTRATION, "alice", "B:demo:read");
    repository.saveAuthorizedClient(tokenForB, this.alice, this.request, this.response);

    assertThat(this.load(repository, API)).isSameAs(tokenForB);
    this.context.setOrg("A");
    assertThat(this.load(repository, API)).isSameAs(tokenForA);
  }

  @Test
  void tokenSavedWhileOtherOrganizationIsCurrent_isStoredForGrantedOrganization() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    this.context.setFunction("demo");
    this.context.setOrg("B");
    final OAuth2AuthorizedClient tokenForA = client(API_REGISTRATION, "alice", "A:demo:read");
    repository.saveAuthorizedClient(tokenForA, this.alice, this.request, this.response);

    assertThat(this.load(repository, API)).isNull();
    this.context.setOrg("A");
    assertThat(this.load(repository, API)).isSameAs(tokenForA);
  }

  @Test
  void tokenSavedWhileOtherFunctionIsCurrent_isStoredForGrantedFunction() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    this.context.setOrg("A");
    this.context.setFunction("other");
    final OAuth2AuthorizedClient token = client(API_REGISTRATION, "alice", "A:demo:read");
    repository.saveAuthorizedClient(token, this.alice, this.request, this.response);

    assertThat(this.load(repository, API)).isNull();
    this.context.setFunction("demo");
    assertThat(this.load(repository, API)).isSameAs(token);
  }

  @Test
  void differentFunctions_areKeptSeparately() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    this.context.setOrg("A");
    this.context.setFunction("f1");
    final OAuth2AuthorizedClient tokenForF1 = client(API_REGISTRATION, "alice", "A:f1:read");
    repository.saveAuthorizedClient(tokenForF1, this.alice, this.request, this.response);

    this.context.setFunction("f2");
    assertThat(this.load(repository, API)).isNull();
    final OAuth2AuthorizedClient tokenForF2 = client(API_REGISTRATION, "alice", "A:f2:read");
    repository.saveAuthorizedClient(tokenForF2, this.alice, this.request, this.response);

    assertThat(this.load(repository, API)).isSameAs(tokenForF2);
    this.context.setFunction("f1");
    assertThat(this.load(repository, API)).isSameAs(tokenForF1);
  }

  @Test
  void singleFunctionApplication_findsTokenSavedFromScope() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository("demo");
    this.context.setOrg("A");
    final OAuth2AuthorizedClient token = client(API_REGISTRATION, "alice", "A:demo:read");
    repository.saveAuthorizedClient(token, this.alice, this.request, this.response);

    assertThat(this.context.getFunction()).isNull();
    assertThat(this.load(repository, API)).isSameAs(token);
  }

  @Test
  void contextFunction_takesPrecedenceOverDefaultFunction() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository("demo");
    this.context.setOrg("A");
    final OAuth2AuthorizedClient token = client(API_REGISTRATION, "alice", "A:demo:read");
    repository.saveAuthorizedClient(token, this.alice, this.request, this.response);

    this.context.setFunction("other");
    assertThat(this.load(repository, API)).isNull();
  }

  @Test
  void registrationWithoutPlaceholders_isFoundAfterOrganizationIsSelected() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository("demo");
    final OAuth2AuthorizedClient loginToken = client(LOGIN_REGISTRATION, "alice", "openid");
    repository.saveAuthorizedClient(loginToken, this.alice, this.request, this.response);

    this.context.setOrg("A");
    this.context.setFunction("other");
    assertThat(this.load(repository, LOGIN)).isSameAs(loginToken);
  }

  @Test
  void registrationWithOrgPlaceholderOnly_ignoresFunction() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    this.context.setOrg("A");
    this.context.setFunction("f1");
    final OAuth2AuthorizedClient token = client(ORG_ONLY_REGISTRATION, "alice", "A:demo:read");
    repository.saveAuthorizedClient(token, this.alice, this.request, this.response);

    this.context.setFunction("f2");
    assertThat(this.load(repository, ORG_ONLY)).isSameAs(token);
    this.context.setOrg("B");
    assertThat(this.load(repository, ORG_ONLY)).isNull();
  }

  @Test
  void tokenWithoutScope_isStoredUnderCurrentContext() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    this.context.setOrg("A");
    this.context.setFunction("demo");
    final OAuth2AuthorizedClient token = client(API_REGISTRATION, "alice");
    repository.saveAuthorizedClient(token, this.alice, this.request, this.response);

    assertThat(this.load(repository, API)).isSameAs(token);
    this.context.setOrg("B");
    assertThat(this.load(repository, API)).isNull();
  }

  @Test
  void grantedScopeNotMatchingTemplate_isStoredUnderCurrentContext() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    this.context.setOrg("A");
    this.context.setFunction("demo");
    final OAuth2AuthorizedClient token = client(API_REGISTRATION, "alice", "B:demo:write", "openid");
    repository.saveAuthorizedClient(token, this.alice, this.request, this.response);

    assertThat(this.load(repository, API)).isSameAs(token);
  }

  @Test
  void ambiguousGrantedScope_isStoredUnderCurrentContext() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    this.context.setOrg("x");
    this.context.setFunction("y");
    // Could be org "a" and function "b:c", or org "a:b" and function "c"
    final OAuth2AuthorizedClient token = client(API_REGISTRATION, "alice", "a:b:c:read");
    repository.saveAuthorizedClient(token, this.alice, this.request, this.response);

    assertThat(this.load(repository, API)).isSameAs(token);
    this.context.setOrg("a");
    this.context.setFunction("b:c");
    assertThat(this.load(repository, API)).isNull();
    this.context.setOrg("a:b");
    this.context.setFunction("c");
    assertThat(this.load(repository, API)).isNull();
  }

  @Test
  void conflictingGrantedScopes_areTreatedAsNoMatch() {
    final ClientRegistration twoScopes = registration("two", "{org}:{function}:read", "{org}:{function}:write");
    final OrgKeyedAuthorizedClientRepository repository = new OrgKeyedAuthorizedClientRepository(
        new InMemoryClientRegistrationRepository(twoScopes), this.context, null);
    this.context.setOrg("C");
    this.context.setFunction("demo");
    final OAuth2AuthorizedClient token = client(twoScopes, "alice", "A:demo:read", "B:demo:write");
    repository.saveAuthorizedClient(token, this.alice, this.request, this.response);

    assertThat(this.load(repository, "two")).isSameAs(token);
  }

  @Test
  void consistentGrantedScopes_areMatched() {
    final ClientRegistration twoScopes = registration("two", "{org}:{function}:read", "{org}:{function}:write");
    final OrgKeyedAuthorizedClientRepository repository = new OrgKeyedAuthorizedClientRepository(
        new InMemoryClientRegistrationRepository(twoScopes), this.context, null);
    this.context.setOrg("C");
    this.context.setFunction("demo");
    final OAuth2AuthorizedClient token = client(twoScopes, "alice", "A:demo:read", "A:demo:write", "openid");
    repository.saveAuthorizedClient(token, this.alice, this.request, this.response);

    assertThat(this.load(repository, "two")).isNull();
    this.context.setOrg("A");
    assertThat(this.load(repository, "two")).isSameAs(token);
  }

  @Test
  void valuesContainingSeparators_doNotCollide() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    // Both tokens have the same scope string, so they are stored under the current context
    this.context.setOrg("a:b");
    this.context.setFunction("c");
    final OAuth2AuthorizedClient first = client(API_REGISTRATION, "alice");
    repository.saveAuthorizedClient(first, this.alice, this.request, this.response);
    this.context.setOrg("a");
    this.context.setFunction("b:c");
    final OAuth2AuthorizedClient second = client(API_REGISTRATION, "alice");
    repository.saveAuthorizedClient(second, this.alice, this.request, this.response);

    assertThat(this.load(repository, API)).isSameAs(second);
    this.context.setOrg("a:b");
    this.context.setFunction("c");
    assertThat(this.load(repository, API)).isSameAs(first);
  }

  @Test
  void userAndRegistrationContainingSeparators_doNotCollide() {
    final ClientRegistration regA = registration("x::y", "openid");
    final ClientRegistration regB = registration("x", "openid");
    final OrgKeyedAuthorizedClientRepository repository = new OrgKeyedAuthorizedClientRepository(
        new InMemoryClientRegistrationRepository(regA, regB), this.context, null);
    final Authentication user1 = user("u");
    final Authentication user2 = user("u::x");
    final OAuth2AuthorizedClient first = client(regA, "u", "openid");
    final OAuth2AuthorizedClient second = client(regB, "u::x", "openid");
    repository.saveAuthorizedClient(first, user1, this.request, this.response);
    repository.saveAuthorizedClient(second, user2, this.request, this.response);

    assertThat((OAuth2AuthorizedClient) repository.loadAuthorizedClient("x::y", user1, this.request)).isSameAs(first);
    assertThat((OAuth2AuthorizedClient) repository.loadAuthorizedClient("x", user2, this.request)).isSameAs(second);
    assertThat((OAuth2AuthorizedClient) repository.loadAuthorizedClient("y", user2, this.request)).isNull();
    assertThat((OAuth2AuthorizedClient) repository.loadAuthorizedClient("x", user1, this.request)).isNull();
  }

  @Test
  void otherUserInSameSession_doesNotGetFirstUsersToken() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    this.context.setOrg("A");
    this.context.setFunction("demo");
    final OAuth2AuthorizedClient aliceToken = client(API_REGISTRATION, "alice", "A:demo:read");
    repository.saveAuthorizedClient(aliceToken, this.alice, this.request, this.response);
    final String oldSessionId = this.request.getSession().getId();

    // Bob logs in on the same browser session, and session fixation protection changes the session ID
    final Authentication bob = user("bob");
    final String newSessionId = this.request.changeSessionId();
    assertThat(newSessionId).isNotEqualTo(oldSessionId);

    assertThat((OAuth2AuthorizedClient) repository.loadAuthorizedClient(API, bob, this.request)).isNull();
    final OAuth2AuthorizedClient bobToken = client(API_REGISTRATION, "bob", "A:demo:read");
    repository.saveAuthorizedClient(bobToken, bob, this.request, this.response);
    assertThat((OAuth2AuthorizedClient) repository.loadAuthorizedClient(API, bob, this.request)).isSameAs(bobToken);
    assertThat(this.load(repository, API)).isSameAs(aliceToken);
  }

  @Test
  void noAuthenticatedUser_loadReturnsNothingAndCreatesNoSession() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    this.context.setOrg("A");
    this.context.setFunction("demo");
    final MockHttpServletRequest noSession = new MockHttpServletRequest();
    final Authentication anonymous = new AnonymousAuthenticationToken(
        "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    final Authentication unauthenticated = new TestingAuthenticationToken("alice", null);

    assertThat((OAuth2AuthorizedClient) repository.loadAuthorizedClient(API, null, noSession)).isNull();
    assertThat((OAuth2AuthorizedClient) repository.loadAuthorizedClient(API, anonymous, noSession)).isNull();
    assertThat((OAuth2AuthorizedClient) repository.loadAuthorizedClient(API, this.alice, noSession)).isNull();
    assertThat(noSession.getSession(false)).isNull();

    // A session with a stored token
    repository.saveAuthorizedClient(
        client(API_REGISTRATION, "alice", "A:demo:read"), this.alice, this.request, this.response);
    assertThat((OAuth2AuthorizedClient) repository.loadAuthorizedClient(API, null, this.request)).isNull();
    assertThat((OAuth2AuthorizedClient) repository.loadAuthorizedClient(API, anonymous, this.request)).isNull();
    assertThat((OAuth2AuthorizedClient) repository.loadAuthorizedClient(API, unauthenticated, this.request)).isNull();
  }

  @Test
  void noAuthenticatedUser_saveStoresNothing() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    final Authentication anonymous = new AnonymousAuthenticationToken(
        "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    repository.saveAuthorizedClient(
        client(LOGIN_REGISTRATION, "anonymousUser", "openid"), anonymous, this.request, this.response);

    assertThat(this.request.getSession(false)).isNull();
  }

  @Test
  void remove_onlyAffectsIdentifiedToken() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    this.context.setFunction("demo");
    final Authentication bob = user("bob");
    final OAuth2AuthorizedClient aliceA = client(API_REGISTRATION, "alice", "A:demo:read");
    final OAuth2AuthorizedClient aliceB = client(API_REGISTRATION, "alice", "B:demo:read");
    final OAuth2AuthorizedClient bobA = client(API_REGISTRATION, "bob", "A:demo:read");
    final OAuth2AuthorizedClient aliceLogin = client(LOGIN_REGISTRATION, "alice", "openid");
    repository.saveAuthorizedClient(aliceA, this.alice, this.request, this.response);
    repository.saveAuthorizedClient(aliceB, this.alice, this.request, this.response);
    repository.saveAuthorizedClient(bobA, bob, this.request, this.response);
    repository.saveAuthorizedClient(aliceLogin, this.alice, this.request, this.response);

    this.context.setOrg("A");
    repository.removeAuthorizedClient(API, this.alice, this.request, this.response);

    assertThat(this.load(repository, API)).isNull();
    assertThat(this.load(repository, LOGIN)).isSameAs(aliceLogin);
    assertThat((OAuth2AuthorizedClient) repository.loadAuthorizedClient(API, bob, this.request)).isSameAs(bobA);
    this.context.setOrg("B");
    assertThat(this.load(repository, API)).isSameAs(aliceB);
  }

  @Test
  void remove_withoutSession_createsNoSession() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    repository.removeAuthorizedClient(API, this.alice, this.request, this.response);
    assertThat(this.request.getSession(false)).isNull();
  }

  @Test
  void invalidatedSession_dropsTokens() {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    final OAuth2AuthorizedClient loginToken = client(LOGIN_REGISTRATION, "alice", "openid");
    repository.saveAuthorizedClient(loginToken, this.alice, this.request, this.response);

    this.request.getSession().invalidate();
    final MockHttpServletRequest next = new MockHttpServletRequest();
    next.setSession(new MockHttpSession());
    assertThat((OAuth2AuthorizedClient) repository.loadAuthorizedClient(LOGIN, this.alice, next)).isNull();
  }

  @Test
  void storedTokens_surviveSessionSerialization() throws Exception {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    this.context.setOrg("A");
    this.context.setFunction("demo");
    repository.saveAuthorizedClient(
        client(API_REGISTRATION, "alice", "A:demo:read"), this.alice, this.request, this.response);

    final Object stored = this.request.getSession().getAttribute(OrgKeyedAuthorizedClientRepository.SESSION_ATTRIBUTE);
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(stored);
    }
    final Object restored;
    try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      restored = in.readObject();
    }

    final MockHttpServletRequest next = new MockHttpServletRequest();
    next.getSession().setAttribute(OrgKeyedAuthorizedClientRepository.SESSION_ATTRIBUTE, restored);
    final OAuth2AuthorizedClient loaded = repository.loadAuthorizedClient(API, this.alice, next);
    assertThat(loaded).isNotNull();
    assertThat(loaded.getAccessToken().getScopes()).containsExactly("A:demo:read");
  }

  @Test
  void concurrentSaves_inOneSession_areAllKept() throws Exception {
    final OrgKeyedAuthorizedClientRepository repository = this.repository();
    this.context.setFunction("demo");
    final MockHttpSession session = new SynchronizedMockHttpSession();
    final int count = 50;
    final ExecutorService executor = Executors.newFixedThreadPool(8);
    final CountDownLatch start = new CountDownLatch(1);
    try {
      final List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        final String org = "org" + i;
        futures.add(executor.submit(() -> {
          final MockHttpServletRequest req = new MockHttpServletRequest();
          req.setSession(session);
          start.await();
          repository.saveAuthorizedClient(
              client(API_REGISTRATION, "alice", org + ":demo:read"), this.alice, req, this.response);
          return null;
        }));
      }
      start.countDown();
      for (final Future<?> f : futures) {
        f.get(10, TimeUnit.SECONDS);
      }
    }
    finally {
      executor.shutdownNow();
    }

    assertThat((Map<?, ?>) session.getAttribute(OrgKeyedAuthorizedClientRepository.SESSION_ATTRIBUTE))
        .hasSize(count);
    final MockHttpServletRequest req = new MockHttpServletRequest();
    req.setSession(session);
    for (int i = 0; i < count; i++) {
      this.context.setOrg("org" + i);
      final OAuth2AuthorizedClient loaded = repository.loadAuthorizedClient(API, this.alice, req);
      assertThat(loaded).isNotNull();
      assertThat(loaded.getAccessToken().getScopes()).containsExactly("org" + i + ":demo:read");
    }
  }

  private OAuth2AuthorizedClient load(final OAuth2AuthorizedClientRepository repository, final String registrationId) {
    return repository.loadAuthorizedClient(registrationId, this.alice, this.request);
  }

  private static Authentication user(final String name) {
    return new TestingAuthenticationToken(name, null, "ROLE_USER");
  }

  private static ClientRegistration registration(final String registrationId, final String... scopes) {
    return ClientRegistration.withRegistrationId(registrationId)
        .clientId("client")
        .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("https://app.example.com/callback")
        .scope(scopes)
        .authorizationUri("https://idp.example.com/auth")
        .tokenUri("https://idp.example.com/token")
        .build();
  }

  private static OAuth2AuthorizedClient client(
      final ClientRegistration registration, final String principalName, final String... scopes) {
    final Instant now = Instant.now();
    final OAuth2AccessToken token = new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER, "token-" + System.nanoTime(), now, now.plusSeconds(300), Set.of(scopes));
    return new OAuth2AuthorizedClient(registration, principalName, token);
  }

  /**
   * Mock session with thread-safe attribute access, as provided by servlet containers.
   */
  private static class SynchronizedMockHttpSession extends MockHttpSession {

    @Override
    public synchronized Object getAttribute(final String name) {
      return super.getAttribute(name);
    }

    @Override
    public synchronized void setAttribute(final String name, final Object value) {
      super.setAttribute(name, value);
    }
  }

}
