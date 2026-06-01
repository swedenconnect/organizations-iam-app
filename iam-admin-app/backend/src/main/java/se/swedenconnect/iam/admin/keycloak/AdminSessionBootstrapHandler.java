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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import se.swedenconnect.iam.admin.auth.OrgRightsOidcUserService;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;
import se.swedenconnect.iam.security.claims.OrgRightsClaimParser;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;

import java.io.IOException;
import java.util.Optional;

/**
 * Authentication success handler that bootstraps session data from the KeyCloak Admin REST API
 * after a successful OIDC login.
 *
 * <p>Extends {@link SavedRequestAwareAuthenticationSuccessHandler} so that the standard
 * Spring Security redirect behaviour (including saved-request restoration) is preserved.</p>
 *
 * <p>If the bootstrap fails with a {@link KeycloakAdminException}, the error is logged and
 * the login proceeds — the session data will simply be absent, and the frontend will handle
 * this gracefully in a later step.</p>
 *
 * @author Martin Lindström
 */
@Component
@Slf4j
public class AdminSessionBootstrapHandler extends SavedRequestAwareAuthenticationSuccessHandler {

  /** HTTP session attribute key under which the bootstrapped data is stored. */
  public static final String SESSION_DATA_ATTR = "adminSessionData";

  /**
   * Resolves the {@link AdminSessionData} from the current HTTP session.
   *
   * @param request the incoming HTTP request
   * @return the session data, or empty if no valid admin session exists
   */
  public static @NonNull Optional<AdminSessionData> resolveSession(final @NonNull HttpServletRequest request) {
    final HttpSession session = request.getSession(false);
    final Object attr = session != null ? session.getAttribute(SESSION_DATA_ATTR) : null;
    return attr instanceof final AdminSessionData data ? Optional.of(data) : Optional.empty();
  }

  private final AdminDataBootstrapService bootstrapService;
  private final OrgRightsClaimParser claimParser;

  public AdminSessionBootstrapHandler(
      final @NonNull AdminDataBootstrapService bootstrapService,
      final @NonNull OrgRightsClaimParser claimParser) {
    this.bootstrapService = bootstrapService;
    this.claimParser = claimParser;
    this.setDefaultTargetUrl("/");
    this.setAlwaysUseDefaultTargetUrl(true);
  }

  @Override
  public void onAuthenticationSuccess(
      final @NonNull HttpServletRequest request,
      final @NonNull HttpServletResponse response,
      final @NonNull Authentication authentication) throws IOException, ServletException {

    if (authentication.getPrincipal() instanceof final OidcUser oidcUser) {
      final String subject = oidcUser.getSubject();
      final OrgRightsClaim claim = this.claimParser.parse(oidcUser.getClaim("org_rights"));

      final HttpSession session = request.getSession(false);
      final String functionConstraint = session != null
          ? (String) session.getAttribute(OrgRightsOidcUserService.SSO_FUNC_ATTR)
          : null;
      final String orgConstraint = session != null
          ? (String) session.getAttribute(OrgRightsOidcUserService.SSO_ORG_ATTR)
          : null;

      try {
        final AdminSessionData sessionData = this.bootstrapService.bootstrap(claim, subject,
            functionConstraint, orgConstraint);
        request.getSession(true).setAttribute(SESSION_DATA_ATTR, sessionData);
      }
      catch (final KeycloakAdminException e) {
        log.error("Session data bootstrap failed for user '{}' — proceeding without data",
            subject, e);
      }
    }

    super.onAuthenticationSuccess(request, response, authentication);
  }

}
