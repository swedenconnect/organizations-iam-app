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

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;
import se.swedenconnect.iam.security.claims.OrgRightsClaimParser;

import java.util.List;

/**
 * OIDC user service that parses the {@code org_rights} claim from the ID token and populates the
 * user's {@link GrantedAuthority} set with {@link se.swedenconnect.iam.security.claims.OrganizationalAuthority}
 * instances.
 *
 * <p>This is a general-purpose library version. It does not enforce admin-only access — that is the
 * application's responsibility. It also does not read SSO session attributes, which is an admin-app-specific
 * concern.</p>
 *
 * <p>Applications that need additional logic (e.g. admin-only enforcement) should either subclass this
 * or provide their own {@link OAuth2UserService} bean, which will suppress this auto-configured one
 * ({@code @ConditionalOnMissingBean}).</p>
 *
 * @author Martin Lindström
 */
@Slf4j
public class OrgRightsOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

  private final OidcUserService delegate = new OidcUserService();
  private final OrgRightsClaimParser claimParser;
  private final @Nullable String functionId;

  /**
   * Constructs an {@code OrgRightsOidcUserService} in full mode.
   *
   * <p>All organizational rights are included as {@link se.swedenconnect.iam.security.claims.OrganizationalAuthority}
   * instances of the form {@code {orgId}:{functionId}:{right}}.</p>
   *
   * @param claimParser the parser to use for extracting org_rights authorities; must not be null
   */
  public OrgRightsOidcUserService(final @NonNull OrgRightsClaimParser claimParser) {
    this(claimParser, null);
  }

  /**
   * Constructs an {@code OrgRightsOidcUserService} in function-scoped mode.
   *
   * <p>When {@code functionId} is set, the {@code org_rights} claim is filtered to entries
   * relevant to that function (exact match or org-wide {@code *}), and authorities are
   * produced as {@link se.swedenconnect.iam.security.claims.FunctionScopedAuthority} instances
   * of the form {@code {orgId}:{right}}.
   * Superusers always receive {@code ROLE_SUPERUSER} regardless of mode.</p>
   *
   * @param claimParser the parser; must not be null
   * @param functionId the function this application is scoped to, or {@code null} for full mode
   */
  public OrgRightsOidcUserService(
      final @NonNull OrgRightsClaimParser claimParser,
      final @Nullable String functionId) {
    this.claimParser = claimParser;
    this.functionId = functionId;
  }

  /**
   * Loads the OIDC user, parses the {@code org_rights} claim, and returns a {@link DefaultOidcUser}
   * populated with {@link se.swedenconnect.iam.security.claims.OrganizationalAuthority} granted authorities.
   *
   * @param userRequest the OIDC user request; must not be null
   * @return the authenticated OIDC user with organizational authorities; never null
   * @throws OAuth2AuthenticationException if user loading fails
   */
  @Override
  public @NonNull OidcUser loadUser(final @NonNull OidcUserRequest userRequest) {
    final OidcUser oidcUser = this.delegate.loadUser(userRequest);
    final String subject = oidcUser.getSubject();

    final OrgRightsClaim claim = this.claimParser.parse(oidcUser.getClaim("org_rights"));
    final List<GrantedAuthority> authorities = this.functionId != null
        ? this.claimParser.buildFunctionScopedAuthorities(claim, this.functionId)
        : this.claimParser.buildAuthorities(claim, null, null);

    log.debug("User '{}' authenticated; authorities: {}", subject, authorities);

    return new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo(), "sub");
  }

}
