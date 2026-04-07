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
package se.swedenconnect.iam.keycloak.resourceaud;

import org.jboss.logging.Logger;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A Keycloak OIDC protocol mapper that sets the {@code aud} claim in the access token based on
 * the OAuth2 {@code resource} parameter (RFC 8707) and the function identifier extracted from
 * the granted scope.
 *
 * <p>Only fires when the {@code resource} parameter is present (from token request form
 * parameters or from the auth session note written by {@link ResourceFunctionExecutor} during
 * the authorization request phase). If {@code resource} is absent, the mapper returns without
 * modifying the {@code aud} claim, leaving Keycloak's default audience intact.</p>
 *
 * <p>This mapper should be added to every OAuth client that requests org-scoped tokens against
 * resource servers. It must be configured to add to the <strong>access token only</strong>.</p>
 *
 * @author Martin Lindström
 */
public class ResourceAudienceMapper extends AbstractOIDCProtocolMapper
    implements OIDCAccessTokenMapper {

  private static final Logger LOG = Logger.getLogger(ResourceAudienceMapper.class);

  public static final String PROVIDER_ID      = "resource-audience-mapper";
  public static final String DISPLAY_TYPE     = "Resource Audience Mapper";
  public static final String DISPLAY_CATEGORY = AbstractOIDCProtocolMapper.TOKEN_MAPPER_CATEGORY;
  public static final String HELP_TEXT =
      "Sets the aud claim in the access token when the OAuth2 resource parameter (RFC 8707) is "
      + "present. If the resource server has client_functions set, aud is [resource, function]. "
      + "If the resource server has no client_functions, aud is [resource, func1, func2, ...] "
      + "for all distinct functions in the granted scope. If resource is absent, aud is not modified.";

  @Override
  public String getId() {
    return PROVIDER_ID;
  }

  @Override
  public String getDisplayType() {
    return DISPLAY_TYPE;
  }

  @Override
  public String getDisplayCategory() {
    return DISPLAY_CATEGORY;
  }

  @Override
  public String getHelpText() {
    return HELP_TEXT;
  }

  @Override
  public List<ProviderConfigProperty> getConfigProperties() {
    return new ArrayList<>();
  }

  @Override
  protected void setClaim(
      final IDToken token,
      final ProtocolMapperModel mappingModel,
      final UserSessionModel userSession,
      final KeycloakSession keycloakSession,
      final ClientSessionContext clientSessionCtx) {

    // 1. Read resource parameter from token request form parameters.
    final var httpRequest = keycloakSession.getContext().getHttpRequest();
    String resource = null;
    if (httpRequest != null) {
      final var formParams = httpRequest.getDecodedFormParameters();
      if (formParams != null) {
        resource = formParams.getFirst("resource");
      }
    }

    // 2. If not found, check auth session note written by the executor during AUTHORIZATION_REQUEST.
    if (resource == null) {
      resource = clientSessionCtx.getClientSession().getNote(ScopeUtils.SESSION_NOTE_KEY);
    }

    // 3. No resource parameter — do not modify aud.
    // The mapper only sets aud when a resource server is explicitly targeted.
    if (resource == null) {
      LOG.debug("No resource parameter present — aud claim not modified");
      return;
    }

    // 4. Look up the resource server client to check for client_functions.
    final var realm = keycloakSession.getContext().getRealm();
    final var resourceClient = keycloakSession.clients().getClientByClientId(realm, resource);

    final String scopeString = clientSessionCtx.getScopeString(false);

    if (resourceClient != null) {
      final String clientFunctions = resourceClient.getAttribute("client_functions");
      if (clientFunctions != null && !clientFunctions.isBlank()) {
        // Client has client_functions — single-function mode.
        final String function = ScopeUtils.extractFunction(scopeString);
        if (function == null) {
          LOG.debug("No org-scoped scope present — aud claim not modified");
          return;
        }
        LOG.debugf("Setting aud to [%s, %s] (client has client_functions)", resource, function);
        token.audience(resource, function);
        return;
      }
    }

    // 5. Client has no client_functions (or client not found) — multi-function mode.
    //    Extract all distinct functions from granted scopes and add each to aud.
    final Set<String> functions = ScopeUtils.extractAllFunctions(scopeString);
    if (functions.isEmpty()) {
      LOG.debug("No org-scoped scopes present — aud claim not modified");
      return;
    }

    // Build aud list: resource first, then each function (if not already equal to resource).
    final List<String> audValues = new ArrayList<>();
    audValues.add(resource);
    for (final String func : functions) {
      if (!audValues.contains(func)) {
        audValues.add(func);
      }
    }

    LOG.debugf("Setting aud to %s (client has no client_functions)", audValues);
    token.audience(audValues.toArray(String[]::new));
  }
}
