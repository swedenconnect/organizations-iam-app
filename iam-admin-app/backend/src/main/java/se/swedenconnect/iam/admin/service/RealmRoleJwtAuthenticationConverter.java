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
package se.swedenconnect.iam.admin.service;

import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Converts a JWT into a {@link JwtAuthenticationToken} with authorities derived from
 * the {@code realm_access.roles} claim.
 *
 * <p>Currently maps the {@code superuser} realm role to {@code ROLE_SUPERUSER}. Other
 * realm roles are ignored. This converter is used by the IAM Service API's resource
 * server filter chain.</p>
 *
 * @author Martin Lindström
 */
public class RealmRoleJwtAuthenticationConverter
    implements Converter<Jwt, AbstractAuthenticationToken> {

  @Override
  public @NonNull AbstractAuthenticationToken convert(final @NonNull Jwt jwt) {
    final Collection<GrantedAuthority> authorities = this.extractAuthorities(jwt);
    return new JwtAuthenticationToken(jwt, authorities);
  }

  private @NonNull Collection<GrantedAuthority> extractAuthorities(final @NonNull Jwt jwt) {
    final List<GrantedAuthority> authorities = new ArrayList<>();
    final Map<String, Object> realmAccess = jwt.getClaim("realm_access");
    if (realmAccess == null) {
      return authorities;
    }
    final Object rolesObj = realmAccess.get("roles");
    if (!(rolesObj instanceof final List<?> roles)) {
      return authorities;
    }
    for (final Object role : roles) {
      if ("superuser".equals(role)) {
        authorities.add(new SimpleGrantedAuthority("ROLE_SUPERUSER"));
      }
    }
    return authorities;
  }

}
