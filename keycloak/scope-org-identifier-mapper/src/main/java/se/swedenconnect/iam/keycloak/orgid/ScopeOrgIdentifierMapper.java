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
package se.swedenconnect.iam.keycloak.orgid;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Keycloak OIDC protocol mapper that adds the {@code organization_identifier} claim to the
 * access token. The claim value is the ten-digit Swedish organizational number extracted from
 * the first granted scope matching the {@code {org}:{function}:{right}} pattern.
 *
 * <p>The claim is added to the <strong>access token only</strong> — not the ID token or
 * UserInfo response. If no scope matching the pattern is present, the claim is simply omitted.</p>
 *
 * @author Martin Lindström
 */
public class ScopeOrgIdentifierMapper extends AbstractOIDCProtocolMapper
    implements OIDCAccessTokenMapper {

  private static final Logger LOG = Logger.getLogger(ScopeOrgIdentifierMapper.class);

  // ---- Mapper identity ----

  public static final String PROVIDER_ID      = "scope-org-identifier-mapper";
  public static final String DISPLAY_TYPE     = "Scope Org Identifier Mapper";
  public static final String DISPLAY_CATEGORY = AbstractOIDCProtocolMapper.TOKEN_MAPPER_CATEGORY;
  public static final String HELP_TEXT =
      "Extracts the organization identifier from the first granted scope matching the "
      + "{org}:{function}:{right} pattern and emits it as the organization_identifier claim "
      + "in the access token.";

  // ---- Token claim name ----

  /** The name of the claim added to the access token. */
  public static final String CLAIM_NAME = "organization_identifier";

  /** Compiled pattern for a scope of the form {@code {10-digit-org}:{function}:{right}}. */
  private static final Pattern SCOPE_PATTERN =
      Pattern.compile("^(\\d{10}):[^:]+:[^:]+$");

  // -------------------------------------------------------------------------

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

    final String scopeString = clientSessionCtx.getScopeString(false);
    if (scopeString == null || scopeString.isBlank()) {
      LOG.debug("No granted scopes — organization_identifier claim not set");
      return;
    }

    for (final String scope : scopeString.split("\\s+")) {
      final Matcher matcher = SCOPE_PATTERN.matcher(scope);
      if (matcher.matches()) {
        final String orgIdentifier = matcher.group(1);
        LOG.debugf("Matched scope '%s' — setting organization_identifier to '%s'",
            scope, orgIdentifier);
        token.getOtherClaims().put(CLAIM_NAME, orgIdentifier);
        return;
      }
    }

    LOG.debug("No scope matching {org}:{function}:{right} pattern found — organization_identifier claim not set");
  }
}
