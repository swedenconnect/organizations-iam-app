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

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import se.swedenconnect.iam.security.claims.OrganizationalAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OrgRightsScopeConverter}.
 *
 * @author Martin Lindström
 */
class OrgRightsScopeConverterTest {

  private final OrgRightsScopeConverter converter = new OrgRightsScopeConverter();

  private List<OrganizationalAuthority> orgAuthorities(final Jwt jwt) {
    return this.converter.convert(jwt).getAuthorities().stream()
        .filter(OrganizationalAuthority.class::isInstance)
        .map(OrganizationalAuthority.class::cast)
        .toList();
  }

  @Test
  void validOrgScopes_parsedToAuthorities() {
    final Jwt jwt = Jwt.withTokenValue("token")
        .header("alg", "none")
        .claim("scope", "5590026042:demo:write 5561234567:demo:read")
        .build();

    final List<OrganizationalAuthority> authorities = this.orgAuthorities(jwt);

    assertThat(authorities).hasSize(2);
    assertThat(authorities)
        .extracting(GrantedAuthority::getAuthority)
        .containsExactlyInAnyOrder("5590026042:demo:write", "5561234567:demo:read");
  }

  @Test
  void mixedScopes_onlyOrgScopesParsed() {
    final Jwt jwt = Jwt.withTokenValue("token")
        .header("alg", "none")
        .claim("scope", "openid profile 5590026042:demo:write")
        .build();

    final List<OrganizationalAuthority> authorities = this.orgAuthorities(jwt);

    assertThat(authorities).hasSize(1);
    assertThat(authorities.getFirst().getAuthority()).isEqualTo("5590026042:demo:write");
  }

  @Test
  void nullScope_returnsEmpty() {
    final Jwt jwt = Jwt.withTokenValue("token")
        .header("alg", "none")
        .claim("sub", "test-user")
        .build();

    final List<OrganizationalAuthority> authorities = this.orgAuthorities(jwt);

    assertThat(authorities).isEmpty();
  }

  @Test
  void blankScope_returnsEmpty() {
    final Jwt jwt = Jwt.withTokenValue("token")
        .header("alg", "none")
        .claim("scope", "")
        .build();

    final List<OrganizationalAuthority> authorities = this.orgAuthorities(jwt);

    assertThat(authorities).isEmpty();
  }

  @Test
  void invalidFormat_silentlyIgnored() {
    final Jwt jwt = Jwt.withTokenValue("token")
        .header("alg", "none")
        .claim("scope", "5590026042:write")
        .build();

    final List<OrganizationalAuthority> authorities = this.orgAuthorities(jwt);

    assertThat(authorities).isEmpty();
  }

  @Test
  void singleValidScope_parsedCorrectly() {
    final Jwt jwt = Jwt.withTokenValue("token")
        .header("alg", "none")
        .claim("scope", "5590026042:*:admin")
        .build();

    final List<OrganizationalAuthority> authorities = this.orgAuthorities(jwt);

    assertThat(authorities).hasSize(1);
    assertThat(authorities.getFirst().getAuthority()).isEqualTo("5590026042:*:admin");
  }

}
