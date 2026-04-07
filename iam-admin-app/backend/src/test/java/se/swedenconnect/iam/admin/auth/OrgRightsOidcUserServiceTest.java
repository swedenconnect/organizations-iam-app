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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.iam.commons.types.OrganizationID;
import se.swedenconnect.iam.security.claims.InsufficientRightsException;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;
import se.swedenconnect.iam.security.claims.OrgRightsClaimParser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrgRightsOidcUserService}.
 *
 * <p>Since the internal {@link org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService}
 * delegate is private and not injectable, the tests focus on the behaviors implemented directly in
 * this class: admin constraint checking and authority building via {@link OrgRightsClaimParser}.</p>
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgRightsOidcUserServiceTest {

  private OrgRightsClaimParser claimParser;

  @Mock
  private HttpServletRequest request;

  @Mock
  private HttpSession httpSession;

  private OrgRightsOidcUserService service;

  @BeforeEach
  void setUp() {
    claimParser = new OrgRightsClaimParser();
    when(request.getSession(false)).thenReturn(null);
    service = new OrgRightsOidcUserService(claimParser, request);
  }

  // ---------------------------------------------------------------------------
  // checkAdminConstraint tests (delegated to OrgRightsClaimParser)
  // ---------------------------------------------------------------------------

  @Test
  void checkAdminConstraint_emptyClaim_throws() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of());

    assertThatThrownBy(() -> claimParser.checkAdminConstraint(claim, null, null))
        .isInstanceOf(InsufficientRightsException.class)
        .hasMessageContaining("no organizational rights");
  }

  @Test
  void checkAdminConstraint_noAdminRight_throws() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042", new OrgRightsClaim.FunctionEntry("walletreg", "read"))
    ));

    assertThatThrownBy(() -> claimParser.checkAdminConstraint(claim, null, null))
        .isInstanceOf(InsufficientRightsException.class);
  }

  @Test
  void checkAdminConstraint_superuser_passes() {
    final OrgRightsClaim claim = new OrgRightsClaim(true, List.of());

    // Must not throw
    claimParser.checkAdminConstraint(claim, null, null);
  }

  @Test
  void checkAdminConstraint_adminRight_passes() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042", new OrgRightsClaim.FunctionEntry("walletreg", "admin"))
    ));

    // Must not throw
    claimParser.checkAdminConstraint(claim, null, null);
  }

  // ---------------------------------------------------------------------------
  // buildAuthorities tests (delegated to OrgRightsClaimParser)
  // ---------------------------------------------------------------------------

  @Test
  void buildAuthorities_withFuncConstraint_filtersToFunction() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042",
            new OrgRightsClaim.FunctionEntry("demo", "admin"),
            new OrgRightsClaim.FunctionEntry("walletreg", "write"))
    ));

    final List<GrantedAuthority> authorities = claimParser.buildAuthorities(claim, null, "demo");

    assertThat(authorities).hasSize(1);
    assertThat(authorities.get(0).getAuthority()).contains("demo");
    assertThat(authorities.get(0).getAuthority()).doesNotContain("walletreg");
  }

  @Test
  void buildAuthorities_withOrgConstraint_filtersToOrg() {
    final OrgRightsClaim claim = new OrgRightsClaim(false, List.of(
        orgEntry("5590026042", new OrgRightsClaim.FunctionEntry("demo", "admin")),
        orgEntry("5561234567", new OrgRightsClaim.FunctionEntry("walletreg", "write"))
    ));

    final List<GrantedAuthority> authorities = claimParser.buildAuthorities(claim, "5590026042", null);

    assertThat(authorities).hasSize(1);
    assertThat(authorities.get(0).getAuthority()).contains("5590026042");
    assertThat(authorities.get(0).getAuthority()).doesNotContain("5561234567");
  }

  @Test
  void buildAuthorities_superuser_receivesRoleSuperuser() {
    final OrgRightsClaim claim = new OrgRightsClaim(true, List.of());

    final List<GrantedAuthority> authorities = claimParser.buildAuthorities(claim, null, null);

    assertThat(authorities).containsExactly(new SimpleGrantedAuthority("ROLE_SUPERUSER"));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static OrgRightsClaim.OrgEntry orgEntry(final String orgId,
      final OrgRightsClaim.FunctionEntry... functions) {
    return new OrgRightsClaim.OrgEntry(OrganizationID.of(orgId), new LocalizedString(), List.of(functions));
  }
}
