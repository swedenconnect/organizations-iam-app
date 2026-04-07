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
package se.swedenconnect.iam.admin.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import se.swedenconnect.iam.security.claims.InsufficientRightsException;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;
import se.swedenconnect.iam.security.claims.OrgRightsClaimParser;

import java.util.List;

/**
 * Admin-app-specific OIDC user service that extends the library's
 * {@link se.swedenconnect.iam.security.client.OrgRightsOidcUserService} with two additional
 * concerns:
 *
 * <ul>
 *   <li>Reading SSO session attributes ({@code sso.org}, {@code sso.func}) to apply optional
 *       organization and function constraints during login</li>
 *   <li>Enforcing admin-only access via {@link OrgRightsClaimParser#checkAdminConstraint}</li>
 * </ul>
 *
 * <p>The session attributes {@code sso.org} and {@code sso.func} are set by
 * {@link se.swedenconnect.iam.admin.controllers.SsoLoginController} when the SSO login path is used.
 * When absent (standard login), all org_rights are considered with no constraint.</p>
 *
 * <p>In both cases the user must hold at least one {@code admin} right within the effective
 * (possibly constrained) claim. If authorization fails, an {@link OAuth2AuthenticationException}
 * is thrown; the error reason is stored in the session for downstream display.</p>
 *
 * <p>Being a {@code @Component} ensures this bean is picked up by Spring, suppressing the
 * auto-configured starter bean via its {@code @ConditionalOnMissingBean}.</p>
 *
 * @author Martin Lindström
 */
@Component
@Slf4j
public class OrgRightsOidcUserService extends se.swedenconnect.iam.security.client.OrgRightsOidcUserService {

  public static final String SSO_ACTIVE_ATTR = "sso.login.active";
  public static final String SSO_ORG_ATTR = "sso.org";
  public static final String SSO_FUNC_ATTR = "sso.func";
  public static final String AUTH_ERROR_ATTR = "auth_error_description";

  private final OidcUserService delegate = new OidcUserService();
  private final OrgRightsClaimParser claimParser;
  private final HttpServletRequest request;

  /**
   * Constructs an {@code OrgRightsOidcUserService}.
   *
   * @param claimParser the library claim parser; must not be null
   * @param request the current HTTP request (for reading session attributes); must not be null
   */
  public OrgRightsOidcUserService(
      final @NonNull OrgRightsClaimParser claimParser,
      final @NonNull HttpServletRequest request) {
    super(claimParser);
    this.claimParser = claimParser;
    this.request = request;
  }

  @Override
  public @NonNull OidcUser loadUser(final @NonNull OidcUserRequest userRequest) {
    final String subject = userRequest.getIdToken().getSubject();

    final HttpSession session = this.request.getSession(false);
    final String orgConstraint = getAttribute(session, SSO_ORG_ATTR);
    final String funcConstraint = getAttribute(session, SSO_FUNC_ATTR);

    // Parse claim early for pre-authorization checks
    final OrgRightsClaim claim = this.claimParser.parse(userRequest.getIdToken().getClaim("org_rights"));

    if (!claim.superuser() && claim.orgEntries().isEmpty()) {
      log.info("Login rejected for '{}': org_rights claim is absent or empty", subject);
      storeError(session, "No organizational rights found in token");
      throw new OAuth2AuthenticationException(
          new OAuth2Error("access_denied"), "No org_rights claim");
    }

    try {
      this.claimParser.checkAdminConstraint(claim, orgConstraint, funcConstraint);
    }
    catch (final InsufficientRightsException e) {
      log.info("Login rejected for '{}': {}", subject, e.getMessage());
      storeError(session, e.getMessage());
      throw new OAuth2AuthenticationException(new OAuth2Error("access_denied"), e.getMessage());
    }

    // Constraint checks passed — build authorities filtered by the SSO constraints
    final OidcUser oidcUser = this.delegate.loadUser(userRequest);
    final List<GrantedAuthority> authorities =
        this.claimParser.buildAuthorities(claim, orgConstraint, funcConstraint);

    log.debug("User '{}' authenticated; authorities: {}", subject, authorities);

    return new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo(), "sub");
  }

  private static @Nullable String getAttribute(final @Nullable HttpSession session, final String key) {
    return session != null ? (String) session.getAttribute(key) : null;
  }

  private static void storeError(final @Nullable HttpSession session, final String description) {
    if (session != null) {
      session.setAttribute(AUTH_ERROR_ATTR, description);
    }
  }

}
