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
import org.keycloak.models.UserModel;
import org.keycloak.services.messages.Messages;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A Keycloak first broker login authenticator that resolves an incoming brokered identity to an
 * existing, pre-provisioned local user by trying a configured list of user attributes in order.
 *
 * <p>Keycloak's stock {@code Detect Existing Broker User} matches on username or email only. Users
 * in this realm are pre-provisioned with an opaque UUID username and no email, so nothing ever
 * matches. This authenticator matches on user attributes instead, for example
 * {@code personalIdentityNumber} and {@code efosId}, so that one identity provider can serve people
 * identified by different attributes.</p>
 *
 * <p>The attributes are tried in the configured order, see {@link MatchRule}. An attribute that is
 * absent from the brokered identity, or that no local user has, moves on to the next one. The first
 * attribute that finds a user decides the outcome, and no later attribute is tried. That attribute
 * refuses the login if it finds more than one user, a disabled user, a user failing its role
 * checks, or a user with a different stored value for an earlier attribute whose value was present
 * in the brokered identity.</p>
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
 * flow, and so does a missing or invalid configuration. The message shown to the user is the same in every failure case and never reveals whether
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

    final List<MatchRule> rules;
    try {
      rules = MatchRule.fromConfig(config);
    }
    catch (final MatchRule.InvalidMatchConfigurationException e) {
      this.failMisconfigured(context, alias, e.getMessage());
      return;
    }
    for (final MatchRule rule : rules) {
      for (final String roleName : Stream.of(rule.requiredRole(), rule.forbiddenRole()).filter(Objects::nonNull).toList()) {
        if (realm.getRole(roleName) == null) {
          this.failMisconfigured(context, alias, "role '%s' configured for attribute '%s' does not exist in realm '%s'"
              .formatted(roleName, rule.attribute(), realm.getName()));
          return;
        }
      }
    }

    if(LOG.isDebugEnabled()) {
      if (brokerContext.getAttributes().isEmpty()){
        LOG.debug("No user attributes found for broker");
      }
      brokerContext.getAttributes().forEach((k, v) -> LOG.debug("BrokerAtt:" + k + ": " + v));
    }

    // The values of the attributes tried so far, which were present but found no user. A user found
    // through a later attribute must not contradict them.
    final Map<String, String> earlierValues = new LinkedHashMap<>();

    for (final MatchRule rule : rules) {
      final String attribute = rule.attribute();
      final String attributeValue = brokerContext.getUserAttribute(attribute);
      if (attributeValue == null || attributeValue.isBlank()) {
        LOG.debugf("[idp-user-matcher] Attribute '%s' is absent from the brokered identity context or empty, "
            + "trying the next attribute", attribute);
        continue;
      }

      // Two results are enough to detect ambiguity; there is no reason to load any more.
      final List<UserModel> matches = session.users()
          .searchForUserByUserAttributeStream(realm, attribute, attributeValue)
          .limit(2)
          .toList();

      if (matches.isEmpty()) {
        LOG.debugf("[idp-user-matcher] No local user has a matching '%s' attribute, trying the next attribute",
            attribute);
        earlierValues.put(attribute, attributeValue);
        continue;
      }

      // This attribute found a user, so it decides the outcome.
      if (matches.size() > 1) {
        this.fail(context, alias,
            "more than one local user has the same '%s' attribute, the match must be unique".formatted(attribute));
        return;
      }

      final UserModel user = matches.getFirst();

      if (!user.isEnabled()) {
        this.fail(context, alias, "user '%s' matched on '%s' is disabled".formatted(user.getId(), attribute));
        return;
      }

      if (rule.requiredRole() != null && !user.hasRole(realm.getRole(rule.requiredRole()))) {
        this.fail(context, alias, "user '%s' matched on '%s' does not hold the required role '%s'"
            .formatted(user.getId(), attribute, rule.requiredRole()));
        return;
      }
      if (rule.forbiddenRole() != null && user.hasRole(realm.getRole(rule.forbiddenRole()))) {
        this.fail(context, alias, "user '%s' matched on '%s' holds the forbidden role '%s'"
            .formatted(user.getId(), attribute, rule.forbiddenRole()));
        return;
      }

      for (final Map.Entry<String, String> earlier : earlierValues.entrySet()) {
        final List<String> stored = user.getAttributeStream(earlier.getKey()).toList();
        if (!stored.isEmpty() && !stored.contains(earlier.getValue())) {
          this.fail(context, alias, "user '%s' matched on '%s' has a different stored '%s' than the brokered identity"
              .formatted(user.getId(), attribute, earlier.getKey()));
          return;
        }
      }

      LOG.debugf(
          "[idp-user-matcher] Identity from provider '%s' matched local user '%s' on attribute '%s', "
              + "handing off to idp-auto-link",
          alias, user.getId(), attribute);

      final ExistingUserInfo existingUser = new ExistingUserInfo(user.getId(), attribute, attributeValue);
      context.getAuthenticationSession().setAuthNote(EXISTING_USER_INFO, existingUser.serialize());
      context.success();
      return;
    }

    this.fail(context, alias, "none of the attributes %s found a local user"
        .formatted(rules.stream().map(MatchRule::attribute).toList()));
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
   * Fails the flow because the matcher configuration is missing or invalid. The user sees the same
   * generic error as for any other failure. The reason is logged as an error, since every login
   * through this flow is refused until an administrator corrects the configuration.
   *
   * @param context the flow context
   * @param alias the identity provider alias, for log correlation
   * @param reason what is wrong with the configuration; server side only
   */
  private void failMisconfigured(
      final @NonNull AuthenticationFlowContext context,
      final @NonNull String alias,
      final @NonNull String reason) {

    LOG.errorf("[idp-user-matcher] Login via identity provider '%s' rejected, the matcher configuration "
        + "is invalid: %s", alias, reason);
    this.sendFailureChallenge(context, Response.Status.FORBIDDEN, FAILURE_EVENT_ERROR, FAILURE_MESSAGE,
        AuthenticationFlowError.INVALID_USER);
  }
}
