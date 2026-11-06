/*
 * Copyright 2026 Sweden Connect
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.swedenconnect.iam.keycloak.idpmatcher;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.broker.AbstractIdpAuthenticator;
import org.keycloak.authentication.authenticators.broker.util.ExistingUserInfo;
import org.keycloak.authentication.authenticators.broker.util.SerializedBrokeredIdentityContext;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.events.EventBuilder;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IdpDetectExistingUserByAttributeAuthenticator}.
 *
 * <p>Most tests use two attributes, {@code personalIdentityNumber} followed by {@code efosId}, so
 * that the order, and that a decision on the first never reaches the second, can be observed.</p>
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdpDetectExistingUserByAttributeAuthenticatorTest {

  private static final String PIN = "personalIdentityNumber";
  private static final String PIN_VALUE = "197001011234";
  private static final String EFOS = "efosId";
  private static final String EFOS_VALUE = "efos-4711";
  private static final String USER_ID = "e6b6f4b0-0000-4000-8000-000000000001";
  private static final String IDP_ALIAS = "eid";

  private IdpDetectExistingUserByAttributeAuthenticator authenticator;

  @Mock
  private AuthenticationFlowContext context;

  @Mock
  private KeycloakSession session;

  @Mock
  private UserProvider userProvider;

  @Mock
  private RealmModel realm;

  @Mock
  private AuthenticationSessionModel authSession;

  @Mock
  private BrokeredIdentityContext brokerContext;

  @Mock
  private IdentityProviderModel idpConfig;

  @Mock
  private SerializedBrokeredIdentityContext serializedCtx;

  @Mock
  private EventBuilder event;

  @Mock
  private LoginFormsProvider loginForms;

  private final Map<String, String> config = new HashMap<>();

  @BeforeEach
  void setUp() {
    this.authenticator = new IdpDetectExistingUserByAttributeAuthenticator();

    this.config.clear();
    this.list(PIN, EFOS);

    final AuthenticatorConfigModel configModel = new AuthenticatorConfigModel();
    configModel.setConfig(this.config);

    when(this.context.getSession()).thenReturn(this.session);
    when(this.context.getRealm()).thenReturn(this.realm);
    when(this.context.getAuthenticationSession()).thenReturn(this.authSession);
    when(this.context.getAuthenticatorConfig()).thenReturn(configModel);
    when(this.context.getEvent()).thenReturn(this.event);
    when(this.context.form()).thenReturn(this.loginForms);

    when(this.session.users()).thenReturn(this.userProvider);
    when(this.realm.getName()).thenReturn("orgiam");

    when(this.brokerContext.getIdpConfig()).thenReturn(this.idpConfig);
    when(this.idpConfig.getAlias()).thenReturn(IDP_ALIAS);
    when(this.brokerContext.getUserAttribute(PIN)).thenReturn(PIN_VALUE);
    when(this.brokerContext.getUserAttribute(EFOS)).thenReturn(EFOS_VALUE);

    when(this.event.user(nullable(UserModel.class))).thenReturn(this.event);
    when(this.loginForms.setError(anyString())).thenReturn(this.loginForms);
    when(this.loginForms.createErrorPage(any())).thenReturn(mock(Response.class));

    // Default: nobody has either value
    this.matching(PIN, PIN_VALUE);
    this.matching(EFOS, EFOS_VALUE);
  }

  // ---------------------------------------------------------------------------
  // Matching in order
  // ---------------------------------------------------------------------------

  /**
   * A configuration of one attribute, as written before the setting became a list, matches exactly
   * as before.
   */
  @Test
  void singleAttribute_uniqueMatch_succeeds() {
    this.list(PIN);
    this.matching(PIN, PIN_VALUE, this.user(USER_ID, true));

    this.authenticate();

    this.assertMatched(PIN);
  }

  /** The first attribute that finds a user decides, and the later one is never looked up. */
  @Test
  void firstAttributeMatches_laterNeverTried() {
    this.matching(PIN, PIN_VALUE, this.user(USER_ID, true));

    this.authenticate();

    this.assertMatched(PIN);
    this.assertNeverSearched(EFOS);
  }

  /** The order of the list is the order the attributes are tried in. */
  @Test
  void orderOfTheListIsTheOrderTried() {
    this.list(EFOS, PIN);
    this.matching(PIN, PIN_VALUE, this.user("pin-user", true));
    this.matching(EFOS, EFOS_VALUE, this.user(USER_ID, true));

    this.authenticate();

    this.assertMatched(EFOS);
    this.assertNeverSearched(PIN);
  }

  /** An attribute absent from the login context moves on to the next one. */
  @Test
  void firstAttributeAbsent_nextMatches() {
    when(this.brokerContext.getUserAttribute(PIN)).thenReturn(null);
    this.matching(EFOS, EFOS_VALUE, this.user(USER_ID, true));

    this.authenticate();

    this.assertMatched(EFOS);
    this.assertNeverSearched(PIN);
  }

  /** An empty value counts as absent. */
  @Test
  void firstAttributeBlank_nextMatches() {
    when(this.brokerContext.getUserAttribute(PIN)).thenReturn("  ");
    this.matching(EFOS, EFOS_VALUE, this.user(USER_ID, true));

    this.authenticate();

    this.assertMatched(EFOS);
    this.assertNeverSearched(PIN);
  }

  /**
   * An attribute that no user has moves on to the next one. The user found there has no stored
   * value for the earlier attribute, which is accepted.
   */
  @Test
  void firstAttributeFindsNobody_nextMatchesUserWithoutThatAttribute() {
    this.matching(EFOS, EFOS_VALUE, this.user(USER_ID, true));

    this.authenticate();

    this.assertMatched(EFOS);
  }

  /** The match handed to idp-auto-link carries the attribute that matched. */
  @Test
  void matchCarriesTheAttributeThatMatched() {
    when(this.brokerContext.getUserAttribute(PIN)).thenReturn(null);
    this.matching(EFOS, EFOS_VALUE, this.user(USER_ID, true));

    this.authenticate();

    final ExistingUserInfo note = this.capturedExistingUserInfo();
    assertEquals(USER_ID, note.getExistingUserId());
    assertEquals(EFOS, note.getDuplicateAttributeName());
    assertEquals(EFOS_VALUE, note.getDuplicateAttributeValue());
  }

  /** No attribute finds a user: refused. */
  @Test
  void noAttributeFindsAUser_fails() {
    this.authenticate();

    this.assertFailedClosed();
  }

  /** No attribute is present in the login context at all: refused. */
  @Test
  void noAttributePresent_fails() {
    when(this.brokerContext.getUserAttribute(PIN)).thenReturn(null);
    when(this.brokerContext.getUserAttribute(EFOS)).thenReturn("");

    this.authenticate();

    this.assertFailedClosed();
    verifyNoInteractions(this.userProvider);
  }

  // ---------------------------------------------------------------------------
  // Refusals by the deciding attribute, which never reach a later one
  // ---------------------------------------------------------------------------

  /** The match must be unique. */
  @Test
  void moreThanOneUser_fails() {
    this.matching(PIN, PIN_VALUE, this.user(USER_ID, true), this.user("another-user-id", true));
    this.matching(EFOS, EFOS_VALUE, this.user(USER_ID, true));

    this.authenticate();

    this.assertFailedClosed();
    this.assertNeverSearched(EFOS);
  }

  /** A disabled account must not be usable. */
  @Test
  void disabledUser_fails() {
    this.matching(PIN, PIN_VALUE, this.user(USER_ID, false));
    this.matching(EFOS, EFOS_VALUE, this.user("other", true));

    this.authenticate();

    this.assertFailedClosed();
    this.assertNeverSearched(EFOS);
  }

  /**
   * A user refused by the forbidden role of the attribute it was found through cannot get in through
   * a later attribute without that restriction.
   */
  @Test
  void forbiddenRoleHeld_fails_andLaterAttributeNeverTried() {
    this.list(PIN + ";forbiddenRole=superuser", EFOS);
    final UserModel user = this.user(USER_ID, true);
    this.withRealmRole("superuser", user, true);
    this.matching(PIN, PIN_VALUE, user);
    this.matching(EFOS, EFOS_VALUE, user);

    this.authenticate();

    this.assertFailedClosed();
    this.assertNeverSearched(EFOS);
  }

  /** The forbidden role is not held, so the match stands. */
  @Test
  void forbiddenRoleNotHeld_succeeds() {
    this.list(PIN + ";forbiddenRole=superuser", EFOS);
    final UserModel user = this.user(USER_ID, true);
    this.withRealmRole("superuser", user, false);
    this.matching(PIN, PIN_VALUE, user);

    this.authenticate();

    this.assertMatched(PIN);
  }

  /** The required role is not held: refused. */
  @Test
  void requiredRoleNotHeld_fails() {
    this.list(PIN, EFOS + ";requiredRole=superuser");
    when(this.brokerContext.getUserAttribute(PIN)).thenReturn(null);
    final UserModel user = this.user(USER_ID, true);
    this.withRealmRole("superuser", user, false);
    this.matching(EFOS, EFOS_VALUE, user);

    this.authenticate();

    this.assertFailedClosed();
  }

  /** The required role is held, so the match stands. */
  @Test
  void requiredRoleHeld_succeeds() {
    this.list(PIN, EFOS + ";requiredRole=superuser");
    when(this.brokerContext.getUserAttribute(PIN)).thenReturn(null);
    final UserModel user = this.user(USER_ID, true);
    this.withRealmRole("superuser", user, true);
    this.matching(EFOS, EFOS_VALUE, user);

    this.authenticate();

    this.assertMatched(EFOS);
  }

  /** Both roles on one entry: the user must hold one and not the other. */
  @Test
  void bothRolesOnOneEntry() {
    this.list(EFOS + ";requiredRole=superuser;forbiddenRole=blocked");
    final UserModel user = this.user(USER_ID, true);
    this.withRealmRole("superuser", user, true);
    this.withRealmRole("blocked", user, true);
    this.matching(EFOS, EFOS_VALUE, user);

    this.authenticate();

    this.assertFailedClosed();
  }

  /** The roles of an entry apply only to users found through that entry. */
  @Test
  void rolesOfAnotherEntryDoNotApply() {
    this.list(PIN + ";forbiddenRole=superuser", EFOS + ";requiredRole=superuser");
    when(this.brokerContext.getUserAttribute(PIN)).thenReturn(null);
    final UserModel user = this.user(USER_ID, true);
    this.withRealmRole("superuser", user, true);
    this.matching(EFOS, EFOS_VALUE, user);

    this.authenticate();

    this.assertMatched(EFOS);
  }

  /**
   * The user found through the later attribute has a stored value for the earlier attribute that
   * differs from the one in the login context: the two identifiers contradict each other.
   */
  @Test
  void contradictingStoredValueForEarlierAttribute_fails() {
    final UserModel user = this.user(USER_ID, true);
    when(user.getAttributeStream(PIN)).thenAnswer(i -> Stream.of("196911292032"));
    this.matching(EFOS, EFOS_VALUE, user);

    this.authenticate();

    this.assertFailedClosed();
  }

  /**
   * An earlier attribute absent from the login context is not compared, whatever the user has
   * stored for it.
   */
  @Test
  void storedValueForAbsentEarlierAttribute_isNotCompared() {
    when(this.brokerContext.getUserAttribute(PIN)).thenReturn(null);
    final UserModel user = this.user(USER_ID, true);
    when(user.getAttributeStream(PIN)).thenAnswer(i -> Stream.of("196911292032"));
    this.matching(EFOS, EFOS_VALUE, user);

    this.authenticate();

    this.assertMatched(EFOS);
  }

  // ---------------------------------------------------------------------------
  // Configuration errors, each refusing every login
  // ---------------------------------------------------------------------------

  /** The execution has no configuration at all. */
  @Test
  void noAuthenticatorConfig_fails() {
    when(this.context.getAuthenticatorConfig()).thenReturn(null);

    this.authenticate();

    this.assertFailedClosed();
    verifyNoInteractions(this.userProvider);
  }

  /** The list is missing. */
  @Test
  void matchAttributeMissing_fails() {
    this.config.remove(IdpDetectExistingUserByAttributeAuthenticatorFactory.MATCH_ATTRIBUTE);

    this.authenticate();

    this.assertFailedClosed();
    verifyNoInteractions(this.userProvider);
  }

  /** The list has no entries. */
  @Test
  void matchAttributeEmpty_fails() {
    this.config.put(IdpDetectExistingUserByAttributeAuthenticatorFactory.MATCH_ATTRIBUTE, "");

    this.authenticate();

    this.assertFailedClosed();
    verifyNoInteractions(this.userProvider);
  }

  /** Each kind of malformed entry refuses every login, even when an earlier entry would match. */
  @ParameterizedTest
  @ValueSource(strings = {
      " ",
      ";forbiddenRole=superuser",
      "efosId;unknown=x",
      "efosId;requiredRole=superuser;requiredRole=superuser",
      "efosId;forbiddenRole=superuser;forbiddenRole=superuser",
      "efosId;requiredRole=",
      "efosId;requiredRole",
      "efosId;",
      "efosId;;requiredRole=superuser",
      "efosId ;requiredRole=superuser",
      "efosId; requiredRole=superuser",
      "efosId;requiredRole =superuser",
      "efosId;requiredRole= superuser",
      "efosId;requiredRole=superuser ",
      " efosId",
  })
  void malformedEntry_fails(final String entry) {
    this.withRealmRole("superuser", this.user("unused", true), true);
    this.list(PIN, entry);
    this.matching(PIN, PIN_VALUE, this.user(USER_ID, true));

    this.authenticate();

    this.assertFailedClosed();
    verifyNoInteractions(this.userProvider);
  }

  /** An empty entry between two others refuses every login. */
  @Test
  void emptyEntryInTheList_fails() {
    this.config.put(IdpDetectExistingUserByAttributeAuthenticatorFactory.MATCH_ATTRIBUTE, PIN + "####" + EFOS);
    this.matching(PIN, PIN_VALUE, this.user(USER_ID, true));

    this.authenticate();

    this.assertFailedClosed();
  }

  /** The same attribute twice refuses every login, whatever roles the two entries carry. */
  @Test
  void sameAttributeTwice_fails() {
    this.list(PIN + ";forbiddenRole=superuser", EFOS, PIN);
    this.withRealmRole("superuser", this.user("unused", true), true);
    this.matching(PIN, PIN_VALUE, this.user(USER_ID, true));

    this.authenticate();

    this.assertFailedClosed();
    verifyNoInteractions(this.userProvider);
  }

  /**
   * A role that does not exist is a misconfiguration, not an absent restriction. This holds even for
   * an entry that would never be reached.
   */
  @Test
  void roleDoesNotExist_fails() {
    when(this.realm.getRole("no-such-role")).thenReturn(null);
    this.list(PIN, EFOS + ";requiredRole=no-such-role");
    this.matching(PIN, PIN_VALUE, this.user(USER_ID, true));

    this.authenticate();

    this.assertFailedClosed();
    verifyNoInteractions(this.userProvider);
  }

  /**
   * The removed matcher-level role settings refuse every login while present, even with an empty
   * value, so that a role restriction is never silently dropped.
   */
  @ParameterizedTest
  @CsvSource({"requiredRole,superuser", "forbiddenRole,superuser", "requiredRole,''", "forbiddenRole,''"})
  void removedRoleSettingPresent_fails(final String key, final String value) {
    this.list(PIN);
    this.matching(PIN, PIN_VALUE, this.user(USER_ID, true));
    this.withRealmRole("superuser", this.user("unused", true), true);
    this.config.put(key, value);

    this.authenticate();

    this.assertFailedClosed();
    verifyNoInteractions(this.userProvider);
  }

  // ---------------------------------------------------------------------------
  // The failure must not be distinguishable by the user
  // ---------------------------------------------------------------------------

  /**
   * Every failure case must show the very same message, so that the error page cannot be used to
   * probe whether an account exists. The message must also not carry any attribute value.
   */
  @Test
  void allFailureCasesShowTheSameGenericMessage() {
    final List<String> messages = new ArrayList<>();

    messages.add(this.failureMessageFor(() -> this.config.remove(
        IdpDetectExistingUserByAttributeAuthenticatorFactory.MATCH_ATTRIBUTE)));
    messages.add(this.failureMessageFor(() -> this.list(PIN + ";bad")));
    messages.add(this.failureMessageFor(() -> this.config.put("forbiddenRole", "superuser")));
    messages.add(this.failureMessageFor(() -> {
      when(this.brokerContext.getUserAttribute(PIN)).thenReturn(null);
      when(this.brokerContext.getUserAttribute(EFOS)).thenReturn(null);
    }));
    messages.add(this.failureMessageFor(() -> { }));
    messages.add(this.failureMessageFor(
        () -> this.matching(PIN, PIN_VALUE, this.user(USER_ID, true), this.user("other", true))));
    messages.add(this.failureMessageFor(() -> this.matching(PIN, PIN_VALUE, this.user(USER_ID, false))));
    messages.add(this.failureMessageFor(() -> {
      this.list(PIN + ";requiredRole=superuser");
      final UserModel user = this.user(USER_ID, true);
      this.matching(PIN, PIN_VALUE, user);
      this.withRealmRole("superuser", user, false);
    }));
    messages.add(this.failureMessageFor(() -> {
      this.list(PIN + ";forbiddenRole=superuser");
      final UserModel user = this.user(USER_ID, true);
      this.matching(PIN, PIN_VALUE, user);
      this.withRealmRole("superuser", user, true);
    }));
    messages.add(this.failureMessageFor(() -> {
      final UserModel user = this.user(USER_ID, true);
      when(user.getAttributeStream(PIN)).thenAnswer(i -> Stream.of("196911292032"));
      this.matching(EFOS, EFOS_VALUE, user);
    }));
    messages.add(this.failureMessageFor(() -> {
      when(this.realm.getRole("no-such-role")).thenReturn(null);
      this.list(PIN + ";requiredRole=no-such-role");
    }));

    assertEquals(1, messages.stream().distinct().count(),
        "all failure cases must show the same message, got: " + messages.stream().distinct().toList());
    assertTrue(messages.stream().noneMatch(m -> m.contains(PIN_VALUE) || m.contains(EFOS_VALUE)),
        "the error message must not echo an attribute value");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void authenticate() {
    this.authenticator.authenticateImpl(this.context, this.serializedCtx, this.brokerContext);
  }

  /** Sets the matchAttribute list, stored the way Keycloak stores a multivalued string. */
  private void list(final String... entries) {
    this.config.put(IdpDetectExistingUserByAttributeAuthenticatorFactory.MATCH_ATTRIBUTE, String.join("##", entries));
  }

  /**
   * Applies the given setup, runs the authenticator on a freshly reset set of interactions, and
   * returns the message key passed to the error page.
   */
  private String failureMessageFor(final Runnable setup) {
    reset(this.context, this.session, this.userProvider, this.realm, this.authSession,
        this.brokerContext, this.idpConfig, this.event, this.loginForms);
    this.setUp();
    setup.run();
    this.authenticate();

    final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(this.loginForms).setError(captor.capture());
    return captor.getValue();
  }

  private UserModel user(final String id, final boolean enabled) {
    final UserModel user = mock(UserModel.class);
    when(user.getId()).thenReturn(id);
    when(user.isEnabled()).thenReturn(enabled);
    when(user.hasRole(any())).thenReturn(false);
    when(user.getAttributeStream(anyString())).thenAnswer(i -> Stream.empty());
    return user;
  }

  private void matching(final String attribute, final String value, final UserModel... users) {
    when(this.userProvider.searchForUserByUserAttributeStream(this.realm, attribute, value))
        .thenAnswer(invocation -> Stream.of(users));
  }

  private void withRealmRole(final String roleName, final UserModel user, final boolean held) {
    final RoleModel role = mock(RoleModel.class);
    when(this.realm.getRole(roleName)).thenReturn(role);
    when(user.hasRole(role)).thenReturn(held);
  }

  private ExistingUserInfo capturedExistingUserInfo() {
    final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(this.authSession).setAuthNote(eq(AbstractIdpAuthenticator.EXISTING_USER_INFO), captor.capture());
    return ExistingUserInfo.deserialize(captor.getValue());
  }

  private void assertMatched(final String attribute) {
    final ExistingUserInfo note = this.capturedExistingUserInfo();
    assertEquals(USER_ID, note.getExistingUserId());
    assertEquals(attribute, note.getDuplicateAttributeName());
    verify(this.context).success();
    verify(this.context, never()).setUser(any());
    verify(this.context, never()).failureChallenge(any(), any());
  }

  private void assertNeverSearched(final String attribute) {
    verify(this.userProvider, never()).searchForUserByUserAttributeStream(any(), eq(attribute), anyString());
  }

  private void assertFailedClosed() {
    verify(this.context).failureChallenge(eq(AuthenticationFlowError.INVALID_USER), any());
    verify(this.context, never()).success();
    verify(this.context, never()).setUser(any());
    verify(this.authSession, never())
        .setAuthNote(eq(AbstractIdpAuthenticator.EXISTING_USER_INFO), anyString());
  }
}
