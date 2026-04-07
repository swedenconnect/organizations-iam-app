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
package se.swedenconnect.iam.security.server;

import org.jspecify.annotations.NonNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import se.swedenconnect.iam.security.claims.OrganizationalAuthority;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * A {@link JwtAuthenticationConverter} for resource servers that extracts
 * {@link OrganizationalAuthority} instances from the {@code scope} claim of an access token.
 *
 * <p>Each scope token matching the pattern {@code {orgIdentifier}:{functionId}:{right}} is parsed
 * into an {@link OrganizationalAuthority}. Scope tokens that do not match this pattern are silently
 * ignored.</p>
 *
 * <p>Wire into a {@code SecurityFilterChain}:</p>
 * <pre>{@code
 * .oauth2ResourceServer(rs -> rs
 *     .jwt(jwt -> jwt.jwtAuthenticationConverter(orgRightsScopeConverter)))
 * }</pre>
 *
 * @author Martin Lindström
 */
public class OrgRightsScopeConverter extends JwtAuthenticationConverter {

  /**
   * Constructs an {@code OrgRightsScopeConverter} and registers the scope-to-authority converter.
   */
  public OrgRightsScopeConverter() {
    this.setJwtGrantedAuthoritiesConverter(this::extractOrganizationalAuthorities);
  }

  /**
   * Extracts {@link OrganizationalAuthority} instances from the {@code scope} claim.
   *
   * @param jwt the access token JWT; must not be null
   * @return the collection of organizational authorities derived from the scope claim; never null
   */
  private @NonNull Collection<GrantedAuthority> extractOrganizationalAuthorities(final @NonNull Jwt jwt) {
    final String scopeClaim = jwt.getClaimAsString("scope");
    if (scopeClaim == null || scopeClaim.isBlank()) {
      return List.of();
    }

    final List<GrantedAuthority> authorities = new ArrayList<>();
    for (final String token : Arrays.asList(scopeClaim.split(" "))) {
      if (token.isBlank()) {
        continue;
      }
      try {
        authorities.add(OrganizationalAuthority.parse(token));
      }
      catch (final IllegalArgumentException ignored) {
        // Not an organizational authority scope token — skip
      }
    }
    return List.copyOf(authorities);
  }

}
