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
import org.jboss.logging.Logger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.broker.AbstractIdpAuthenticator;
import org.keycloak.authentication.authenticators.broker.util.ExistingUserInfo;
import org.keycloak.authentication.authenticators.broker.util.SerializedBrokeredIdentityContext;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.events.Errors;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.messages.Messages;

import java.util.List;

/**
 * A Keycloak first broker login authenticator that resolves an incoming brokered identity to an
 * existing, pre-provisioned local user by matching on a configured user attribute.
 *
 * <p>Keycloak's stock {@code Detect Existing Broker User} matches on username or email only. Users
 * in this realm are pre-provisioned with an opaque UUID username and no email, so nothing ever
 * matches. This authenticator matches on a user attribute instead — typically
 * {@code personalIdentityNumber}.</p>
 *
 * <p>The attribute value is read from the {@link BrokeredIdentityContext}, where the stock IdP
 * mappers ({@code Attribute Importer} for SAML, the claim mapper for OIDC) put it before the first
 * broker login flow runs. The authenticator therefore contains no protocol specific code and serves
 * SAML and OIDC providers alike.</p>
 *
 * <p>On a unique match that passes every check, the matched user is written to the
 * {@link AbstractIdpAuthenticator#EXISTING_USER_INFO} auth note in the same form
 * {@code IdpCreateUserIfUniqueAuthenticator} uses. The stock {@code Automatically Set Existing
 * User} ({@code idp-auto-link}) execution, placed directly after this one in the flow, reads the
 * note and sets the user. This authenticator never sets the user itself, never creates users, and
 * never writes federated identity links — Keycloak does the linking as part of normal post first
 * broker login handling.</p>
 *
 * <p><strong>Fail closed.</strong> Every outcome other than a unique, permitted match fails the
 * flow. The message shown to the user is the same in every failure case and never reveals whether
 * an account exists or echoes the matched value; the specific reason goes to the server log
 * only.</p>
 *
 * <p>All behaviour is driven by the authenticator configuration, so a single deployed instance
 * serves several identity providers with different configurations. No per provider state is
 * held.</p>
 *
 * @author Martin Lindström
 */
public class IdpDetectExistingUserByAttributeAuthenticator extends AbstractIdpAuthenticator {

  private static final Logger LOG = Logger.getLogger(IdpDetectExistingUserByAttributeAuthenticator.class);

  /**
   * The event error reported in every failure case. Deliberately uniform: the reason is written to
   * the server log instead, so that neither the error page nor the admin event stream distinguishes
   * "no such user" from "user exists but is not permitted".
   */
  private static final String FAILURE_EVENT_ERROR = Errors.INVALID_USER;

  /**
   * The message key shown to the user in every failure case. Generic by design — it must not reveal
   * whether an account exists.
   */
  private static final String FAILURE_MESSAGE = Messages.IDENTITY_PROVIDER_UNEXPECTED_ERROR;

  @Override
  protected void authenticateImpl(
      final AuthenticationFlowContext context,
      final SerializedBrokeredIdentityContext serializedCtx,
      final BrokeredIdentityContext brokerContext) {

    final KeycloakSession session = context.getSession();
    final RealmModel realm = context.getRealm();
    final AuthenticatorConfigModel config = context.getAuthenticatorConfig();

    final String alias = brokerContext.getIdpConfig().getAlias();

    final String matchAttribute = configValue(config, IdpDetectExistingUserByAttributeAuthenticatorFactory.MATCH_ATTRIBUTE);
    if (matchAttribute == null) {
      this.fail(context, alias, "authenticator is not configured with a match attribute");
      return;
    }

    final String attributeValue = brokerContext.getUserAttribute(matchAttribute);
    if (attributeValue == null || attributeValue.isBlank()) {
      this.fail(context, alias,
          "attribute '%s' is absent from the brokered identity context or empty — check the identity provider's attribute mappers"
              .formatted(matchAttribute));
      return;
    }

    // Two results are enough to detect ambiguity; there is no reason to load any more.
    final List<UserModel> matches = session.users()
        .searchForUserByUserAttributeStream(realm, matchAttribute, attributeValue)
        .limit(2)
        .toList();

    if (matches.isEmpty()) {
      this.fail(context, alias, "no local user has a matching '%s' attribute".formatted(matchAttribute));
      return;
    }
    if (matches.size() > 1) {
      this.fail(context, alias,
          "more than one local user has the same '%s' attribute — the match must be unique".formatted(matchAttribute));
      return;
    }

    final UserModel user = matches.getFirst();

    if (!user.isEnabled()) {
      this.fail(context, alias, "matched user '%s' is disabled".formatted(user.getId()));
      return;
    }

    final String requiredRole = configValue(config, IdpDetectExistingUserByAttributeAuthenticatorFactory.REQUIRED_ROLE);
    if (requiredRole != null) {
      final RoleModel role = realm.getRole(requiredRole);
      if (role == null) {
        this.fail(context, alias,
            "configured required role '%s' does not exist in realm '%s'".formatted(requiredRole, realm.getName()));
        return;
      }
      if (!user.hasRole(role)) {
        this.fail(context, alias,
            "matched user '%s' does not hold the required role '%s'".formatted(user.getId(), requiredRole));
        return;
      }
    }

    final String forbiddenRole = configValue(config, IdpDetectExistingUserByAttributeAuthenticatorFactory.FORBIDDEN_ROLE);
    if (forbiddenRole != null) {
      final RoleModel role = realm.getRole(forbiddenRole);
      if (role == null) {
        this.fail(context, alias,
            "configured forbidden role '%s' does not exist in realm '%s'".formatted(forbiddenRole, realm.getName()));
        return;
      }
      if (user.hasRole(role)) {
        this.fail(context, alias,
            "matched user '%s' holds the forbidden role '%s'".formatted(user.getId(), forbiddenRole));
        return;
      }
    }

    LOG.debugf(
        "[idp-user-matcher] Identity from provider '%s' matched local user '%s' on attribute '%s' — "
            + "handing off to idp-auto-link",
        alias, user.getId(), matchAttribute);

    final ExistingUserInfo existingUser = new ExistingUserInfo(user.getId(), matchAttribute, attributeValue);
    context.getAuthenticationSession().setAuthNote(EXISTING_USER_INFO, existingUser.serialize());
    context.success();
  }

  @Override
  protected void actionImpl(
      final AuthenticationFlowContext context,
      final SerializedBrokeredIdentityContext serializedCtx,
      final BrokeredIdentityContext brokerContext) {
    this.authenticateImpl(context, serializedCtx, brokerContext);
  }

  @Override
  public boolean requiresUser() {
    return false;
  }

  @Override
  public boolean configuredFor(
      final KeycloakSession session, final RealmModel realm, final UserModel user) {
    return true;
  }

  /**
   * Fails the flow with the generic user-facing error, logging the actual reason at the server.
   *
   * <p>The log message names the failing check but never contains the matched attribute value.</p>
   *
   * @param context the flow context
   * @param alias the identity provider alias, for log correlation
   * @param reason the reason the match was rejected; server side only
   */
  private void fail(
      final @NonNull AuthenticationFlowContext context,
      final @NonNull String alias,
      final @NonNull String reason) {

    LOG.infof("[idp-user-matcher] Login via identity provider '%s' rejected: %s", alias, reason);
    this.sendFailureChallenge(context, Response.Status.FORBIDDEN, FAILURE_EVENT_ERROR, FAILURE_MESSAGE,
        AuthenticationFlowError.INVALID_USER);
  }

  /**
   * Reads a configuration value, normalizing an absent, empty or blank value to {@code null}.
   *
   * @param config the authenticator configuration, may be {@code null} if the execution has none
   * @param key the configuration key
   * @return the trimmed value, or {@code null} if not configured
   */
  static @Nullable String configValue(
      final @Nullable AuthenticatorConfigModel config, final @NonNull String key) {
    if (config == null || config.getConfig() == null) {
      return null;
    }
    final String value = config.getConfig().get(key);
    return value == null || value.isBlank() ? null : value.trim();
  }
}
