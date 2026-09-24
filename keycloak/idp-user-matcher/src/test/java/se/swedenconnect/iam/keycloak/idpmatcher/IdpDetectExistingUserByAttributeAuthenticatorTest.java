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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IdpDetectExistingUserByAttributeAuthenticator}.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdpDetectExistingUserByAttributeAuthenticatorTest {

  private static final String ATTRIBUTE = "personalIdentityNumber";
  private static final String ATTRIBUTE_VALUE = "197001011234";
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
    this.config.put(IdpDetectExistingUserByAttributeAuthenticatorFactory.MATCH_ATTRIBUTE, ATTRIBUTE);

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
    when(this.brokerContext.getUserAttribute(ATTRIBUTE)).thenReturn(ATTRIBUTE_VALUE);

    when(this.event.user(nullable(UserModel.class))).thenReturn(this.event);
    when(this.loginForms.setError(anyString())).thenReturn(this.loginForms);
    when(this.loginForms.createErrorPage(any())).thenReturn(mock(Response.class));

    // Default: exactly one matching, enabled user
    this.matchingUsers(this.user(USER_ID, true));
  }

  // ---------------------------------------------------------------------------
  // Success paths
  // ---------------------------------------------------------------------------

  /**
   * Exactly one enabled user matches: the match is written to the EXISTING_USER_INFO auth note for
   * the stock idp-auto-link execution to pick up. The authenticator must not set the user itself.
   */
  @Test
  void uniqueMatch_setsExistingUserInfoNoteAndSucceeds() {
    this.authenticate();

    final ExistingUserInfo note = this.capturedExistingUserInfo();
    assertNotNull(note);
    assertEquals(USER_ID, note.getExistingUserId());
    assertEquals(ATTRIBUTE, note.getDuplicateAttributeName());

    verify(this.context).success();
    verify(this.context, never()).setUser(any());
    verify(this.context, never()).failureChallenge(any(), any());
  }

  /** The matched user holds the required role, so the match stands. */
  @Test
  void requiredRoleHeld_succeeds() {
    final UserModel user = this.user(USER_ID, true);
    this.matchingUsers(user);
    this.withRealmRole("superuser", user, true);
    this.config.put(IdpDetectExistingUserByAttributeAuthenticatorFactory.REQUIRED_ROLE, "superuser");

    this.authenticate();

    verify(this.context).success();
    assertEquals(USER_ID, this.capturedExistingUserInfo().getExistingUserId());
  }

  /** The matched user does not hold the forbidden role, so the match stands. */
  @Test
  void forbiddenRoleNotHeld_succeeds() {
    final UserModel user = this.user(USER_ID, true);
    this.matchingUsers(user);
    this.withRealmRole("superuser", user, false);
    this.config.put(IdpDetectExistingUserByAttributeAuthenticatorFactory.FORBIDDEN_ROLE, "superuser");

    this.authenticate();

    verify(this.context).success();
  }

  // ---------------------------------------------------------------------------
  // Failure paths — each must fail closed
  // ---------------------------------------------------------------------------

  /** No match attribute configured at all. */
  @Test
  void noMatchAttributeConfigured_fails() {
    this.config.remove(IdpDetectExistingUserByAttributeAuthenticatorFactory.MATCH_ATTRIBUTE);

    this.authenticate();

    this.assertFailedClosed();
  }

  /** The authenticator has no configuration model at all. */
  @Test
  void noAuthenticatorConfig_fails() {
    when(this.context.getAuthenticatorConfig()).thenReturn(null);

    this.authenticate();

    this.assertFailedClosed();
  }

  /** The configured attribute was never placed in the brokered context by an IdP mapper. */
  @Test
  void attributeAbsentFromBrokeredContext_fails() {
    when(this.brokerContext.getUserAttribute(ATTRIBUTE)).thenReturn(null);

    this.authenticate();

    this.assertFailedClosed();
  }

  /** The attribute is present but blank. */
  @Test
  void attributeEmpty_fails() {
    when(this.brokerContext.getUserAttribute(ATTRIBUTE)).thenReturn("   ");

    this.authenticate();

    this.assertFailedClosed();
  }

  /** No local user carries the value — nobody may self-provision by authenticating. */
  @Test
  void noLocalUserMatches_fails() {
    this.matchingUsers();

    this.authenticate();

    this.assertFailedClosed();
  }

  /** The match must be unique. */
  @Test
  void multipleLocalUsersMatch_fails() {
    this.matchingUsers(this.user(USER_ID, true), this.user("another-user-id", true));

    this.authenticate();

    this.assertFailedClosed();
  }

  /** A disabled account must not be usable. */
  @Test
  void matchedUserDisabled_fails() {
    this.matchingUsers(this.user(USER_ID, false));

    this.authenticate();

    this.assertFailedClosed();
  }

  /** The required role is configured but the matched user does not hold it. */
  @Test
  void requiredRoleNotHeld_fails() {
    final UserModel user = this.user(USER_ID, true);
    this.matchingUsers(user);
    this.withRealmRole("superuser", user, false);
    this.config.put(IdpDetectExistingUserByAttributeAuthenticatorFactory.REQUIRED_ROLE, "superuser");

    this.authenticate();

    this.assertFailedClosed();
  }

  /**
   * A required role name that does not exist in the realm is a misconfiguration, not an absent
   * constraint — it must fail rather than silently letting everyone through.
   */
  @Test
  void requiredRoleDoesNotExist_fails() {
    when(this.realm.getRole("no-such-role")).thenReturn(null);
    this.config.put(IdpDetectExistingUserByAttributeAuthenticatorFactory.REQUIRED_ROLE, "no-such-role");

    this.authenticate();

    this.assertFailedClosed();
  }

  /** The forbidden role is configured and the matched user holds it. */
  @Test
  void forbiddenRoleHeld_fails() {
    final UserModel user = this.user(USER_ID, true);
    this.matchingUsers(user);
    this.withRealmRole("superuser", user, true);
    this.config.put(IdpDetectExistingUserByAttributeAuthenticatorFactory.FORBIDDEN_ROLE, "superuser");

    this.authenticate();

    this.assertFailedClosed();
  }

  /**
   * A forbidden role name that does not exist in the realm must fail too — otherwise the guard
   * separating the two user populations would quietly stop guarding.
   */
  @Test
  void forbiddenRoleDoesNotExist_fails() {
    when(this.realm.getRole("no-such-role")).thenReturn(null);
    this.config.put(IdpDetectExistingUserByAttributeAuthenticatorFactory.FORBIDDEN_ROLE, "no-such-role");

    this.authenticate();

    this.assertFailedClosed();
  }

  // ---------------------------------------------------------------------------
  // The failure must not be distinguishable by the user
  // ---------------------------------------------------------------------------

  /**
   * Every failure case must show the very same message, so that the error page cannot be used to
   * probe whether an account exists. The message must also not carry the matched value.
   */
  @Test
  void allFailureCasesShowTheSameGenericMessage() {
    final List<String> messages = new ArrayList<>();

    messages.add(this.failureMessageFor(() -> this.config.remove(
        IdpDetectExistingUserByAttributeAuthenticatorFactory.MATCH_ATTRIBUTE)));
    messages.add(this.failureMessageFor(
        () -> when(this.brokerContext.getUserAttribute(ATTRIBUTE)).thenReturn(null)));
    messages.add(this.failureMessageFor(
        () -> when(this.brokerContext.getUserAttribute(ATTRIBUTE)).thenReturn("")));
    messages.add(this.failureMessageFor(this::matchingUsers));
    messages.add(this.failureMessageFor(
        () -> this.matchingUsers(this.user(USER_ID, true), this.user("other", true))));
    messages.add(this.failureMessageFor(() -> this.matchingUsers(this.user(USER_ID, false))));
    messages.add(this.failureMessageFor(() -> {
      final UserModel user = this.user(USER_ID, true);
      this.matchingUsers(user);
      this.withRealmRole("superuser", user, false);
      this.config.put(IdpDetectExistingUserByAttributeAuthenticatorFactory.REQUIRED_ROLE, "superuser");
    }));
    messages.add(this.failureMessageFor(() -> {
      final UserModel user = this.user(USER_ID, true);
      this.matchingUsers(user);
      this.withRealmRole("superuser", user, true);
      this.config.put(IdpDetectExistingUserByAttributeAuthenticatorFactory.FORBIDDEN_ROLE, "superuser");
    }));
    messages.add(this.failureMessageFor(() -> {
      when(this.realm.getRole("no-such-role")).thenReturn(null);
      this.config.put(IdpDetectExistingUserByAttributeAuthenticatorFactory.REQUIRED_ROLE, "no-such-role");
    }));

    assertEquals(1, messages.stream().distinct().count(),
        "all failure cases must show the same message, got: " + messages.stream().distinct().toList());
    assertTrue(messages.stream().noneMatch(m -> m.contains(ATTRIBUTE_VALUE)),
        "the error message must not echo the matched attribute value");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void authenticate() {
    this.authenticator.authenticateImpl(this.context, this.serializedCtx, this.brokerContext);
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
    return user;
  }

  private void matchingUsers(final UserModel... users) {
    when(this.userProvider.searchForUserByUserAttributeStream(this.realm, ATTRIBUTE, ATTRIBUTE_VALUE))
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

  private void assertFailedClosed() {
    verify(this.context).failureChallenge(eq(AuthenticationFlowError.INVALID_USER), any());
    verify(this.context, never()).success();
    verify(this.context, never()).setUser(any());
    verify(this.authSession, never())
        .setAuthNote(eq(AbstractIdpAuthenticator.EXISTING_USER_INFO), anyString());
  }
}
