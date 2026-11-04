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
package se.swedenconnect.iam.admin.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;
import se.swedenconnect.iam.admin.audit.AuditData;
import se.swedenconnect.iam.admin.audit.RecordingAuditPublisher;
import se.swedenconnect.iam.admin.audit.SessionCorrelationId;
import se.swedenconnect.iam.admin.auth.OrgRightsOidcUserService;
import se.swedenconnect.iam.admin.keycloak.AdminDataBootstrapService;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;
import se.swedenconnect.iam.security.claims.OrgRightsClaimParser;
import se.swedenconnect.spring.audit.AuditEvent;
import se.swedenconnect.spring.audit.repository.AuditEventWriteException;
import se.swedenconnect.spring.audit.tracing.CorrelationID;
import se.swedenconnect.spring.audit.tracing.CorrelationIDHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static se.swedenconnect.iam.admin.audit.RecordingAuditPublisher.object;

/**
 * Tests the audit events of the login flow: {@code login_succeeded}, {@code login_rejected},
 * {@code login_failed} and {@code logout}.
 *
 * @author Martin Lindström
 */
class LoginAuditTest {

  private static final String SUBJECT = "subject-1";

  private final RecordingAuditPublisher publisher = new RecordingAuditPublisher();

  private MockHttpServletRequest request;

  private MockHttpSession session;

  private SecurityConfiguration securityConfiguration;

  @BeforeEach
  void setUp() {
    this.session = new MockHttpSession();
    this.request = new MockHttpServletRequest();
    this.request.setSession(this.session);
    this.securityConfiguration = new SecurityConfiguration(this.publisher);
    ReflectionTestUtils.setField(this.securityConfiguration, "contextPath", "/");
  }

  @AfterEach
  void cleanUp() {
    SecurityContextHolder.clearContext();
    CorrelationIDHolder.clear();
  }

  /**
   * A rejected login produces {@code login_rejected} with the subject as principal, and passing
   * through the failure handler afterwards does not add a {@code login_failed}.
   */
  @Test
  void rejectedLogin_onlyLoginRejected() throws Exception {
    this.session.setAttribute(OrgRightsOidcUserService.SSO_ACTIVE_ATTR, Boolean.TRUE);
    this.session.setAttribute(OrgRightsOidcUserService.SSO_ORG_ATTR, "5565552014");
    final OrgRightsOidcUserService service =
        new OrgRightsOidcUserService(new OrgRightsClaimParser(), this.request, this.publisher);

    final OAuth2AuthenticationException exception = (OAuth2AuthenticationException) org.assertj.core.api.Assertions
        .catchThrowable(() -> service.loadUser(userRequest(List.of())));
    this.securityConfiguration.handleAuthenticationFailure(this.request, new MockHttpServletResponse(), exception);

    assertThat(this.publisher.types()).containsExactly("login_rejected");
    final AuditEvent event = this.publisher.single("login_rejected");
    assertThat(event.getPrincipal()).isEqualTo(SUBJECT);
    assertThat(object(event, "user"))
        .containsExactly(Map.entry("user_id", SUBJECT), Map.entry("display_name", "Klara Karlsson"));
    assertThat(object(event, "login")).containsExactly(
        Map.entry("method", "sso"),
        Map.entry("superuser", false),
        Map.entry("organization_constraint", "5565552014"),
        Map.entry("reason", "no_org_rights"));
  }

  /** A user holding rights but no admin right is rejected with {@code insufficient_admin_rights}. */
  @Test
  void rejectedLogin_insufficientAdminRights() {
    final OrgRightsOidcUserService service =
        new OrgRightsOidcUserService(new OrgRightsClaimParser(), this.request, this.publisher);
    final List<Map<String, Object>> rawClaim = List.of(Map.of(
        "organization_identifier", "2021006883",
        "functions", List.of(Map.of("function", "walletreg", "right", "read"))));

    assertThatThrownBy(() -> service.loadUser(userRequest(rawClaim)))
        .isInstanceOf(OAuth2AuthenticationException.class);

    assertThat(object(this.publisher.single("login_rejected"), "login"))
        .containsEntry("method", "standard")
        .containsEntry("reason", "insufficient_admin_rights");
  }

  /** Any other failure is {@code login_failed}, with the subject when it is known. */
  @Test
  void otherFailure_loginFailedWithKnownSubject() throws Exception {
    this.request.setAttribute(OrgRightsOidcUserService.LOGIN_USER_ATTR, AuditData.User.of(SUBJECT, "Klara Karlsson"));
    CorrelationIDHolder.set(CorrelationID.of("flow-1"));

    this.securityConfiguration.handleAuthenticationFailure(
        this.request, new MockHttpServletResponse(), new BadCredentialsException("bad"));

    final AuditEvent event = this.publisher.single("login_failed");
    assertThat(event.getPrincipal()).isEqualTo(SUBJECT);
    assertThat(object(event, "user"))
        .containsExactly(Map.entry("user_id", SUBJECT), Map.entry("display_name", "Klara Karlsson"));
    assertThat(event.getCorrelationId()).isEqualTo(CorrelationID.of("flow-1"));
    assertThat(object(event, "error"))
        .containsEntry("code", "authentication_failed")
        .containsEntry("exception_class", BadCredentialsException.class.getName());
  }

  /** A failure before the subject is known carries no principal. */
  @Test
  void otherFailure_unknownSubject_noPrincipal() throws Exception {
    this.securityConfiguration.handleAuthenticationFailure(
        this.request, new MockHttpServletResponse(), new BadCredentialsException("bad"));

    assertThat(this.publisher.single("login_failed").getPrincipal()).isEmpty();
    assertThat(this.publisher.single("login_failed").getData()).doesNotContainKey("user");
  }

  /** A completed login produces {@code login_succeeded} with the session's correlation ID. */
  @Test
  void successfulLogin_loginSucceeded() throws Exception {
    final AdminDataBootstrapService bootstrapService = mock(AdminDataBootstrapService.class);
    when(bootstrapService.bootstrap(any(), anyString(), any(), any())).thenReturn(
        new AdminSessionData(true, null, null, List.of(), Set.of(), new OrgRightsClaim(true, List.of())));
    SessionCorrelationId.assign(this.session, "flow-2");

    new AdminSessionBootstrapHandler(bootstrapService, new OrgRightsClaimParser(), this.publisher)
        .onAuthenticationSuccess(this.request, new MockHttpServletResponse(), authentication());

    assertThat(this.publisher.types()).containsExactly("login_succeeded");
    final AuditEvent event = this.publisher.single("login_succeeded");
    assertThat(event.getPrincipal()).isEqualTo(SUBJECT);
    assertThat(object(event, "user"))
        .containsExactly(Map.entry("user_id", SUBJECT), Map.entry("display_name", "Klara Karlsson"));
    assertThat(event.getCorrelationId()).isEqualTo(CorrelationID.of("flow-2"));
    assertThat(object(event, "login")).containsEntry("method", "standard").containsEntry("superuser", true);
  }

  /** A login whose session data cannot be set up still succeeds, and raises a system alert. */
  @Test
  void successfulLogin_bootstrapFailure_systemAlert() throws Exception {
    final AdminDataBootstrapService bootstrapService = mock(AdminDataBootstrapService.class);
    when(bootstrapService.bootstrap(any(), anyString(), any(), any())).thenThrow(new KeycloakAdminException("down"));

    new AdminSessionBootstrapHandler(bootstrapService, new OrgRightsClaimParser(), this.publisher)
        .onAuthenticationSuccess(this.request, new MockHttpServletResponse(), authentication());

    assertThat(this.publisher.types()).containsExactly("login_succeeded", "system_alert");
  }

  /** A login that cannot be audited is stopped: the session is invalidated and the error propagates. */
  @Test
  void successfulLogin_auditWriteFailure_stopsLogin() {
    this.publisher.failWritesOf("login_succeeded");
    final AdminDataBootstrapService bootstrapService = mock(AdminDataBootstrapService.class);
    SecurityContextHolder.getContext().setAuthentication(authentication());

    assertThatThrownBy(() -> new AdminSessionBootstrapHandler(bootstrapService, new OrgRightsClaimParser(),
        this.publisher).onAuthenticationSuccess(this.request, new MockHttpServletResponse(), authentication()))
        .isInstanceOf(AuditEventWriteException.class);

    assertThat(this.session.isInvalid()).isTrue();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  /** A logout produces {@code logout} with the user as principal and the correlation ID still set. */
  @Test
  void logout_carriesPrincipalAndCorrelationId() throws Exception {
    CorrelationIDHolder.set(CorrelationID.of("flow-3"));

    this.securityConfiguration.handleLogoutSuccess(this.request, new MockHttpServletResponse(), authentication());

    final AuditEvent event = this.publisher.single("logout");
    assertThat(event.getPrincipal()).isEqualTo(SUBJECT);
    assertThat(event.getCorrelationId()).isEqualTo(CorrelationID.of("flow-3"));
    assertThat(object(event, "user"))
        .containsExactly(Map.entry("user_id", SUBJECT), Map.entry("display_name", "Klara Karlsson"));
  }

  /** A logout without a logged-in user produces nothing. */
  @Test
  void logout_withoutAuthentication_nothing() throws Exception {
    this.securityConfiguration.handleLogoutSuccess(this.request, new MockHttpServletResponse(), null);

    assertThat(this.publisher.events()).isEmpty();
  }

  private static Authentication authentication() {
    final OidcIdToken idToken = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(60),
        Map.of("sub", SUBJECT, "name", "Klara Karlsson", "org_rights", List.of(Map.of("superuser", true))));
    final OidcUser user = new DefaultOidcUser(List.of(), idToken);
    return new OAuth2AuthenticationToken(user, List.of(), "iam-admin");
  }

  private static OidcUserRequest userRequest(final Object rawClaim) {
    final Instant issuedAt = Instant.now();
    final ClientRegistration registration = ClientRegistration.withRegistrationId("iam-admin")
        .clientId("iam-admin")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("https://localhost/login/oauth2/code/iam-admin")
        .authorizationUri("https://localhost/authorize")
        .tokenUri("https://localhost/token")
        .build();
    final OidcIdToken idToken = new OidcIdToken("id-token", issuedAt, issuedAt.plusSeconds(300),
        Map.of("sub", SUBJECT, "given_name", "Klara", "family_name", "Karlsson", "org_rights", rawClaim));
    return new OidcUserRequest(registration,
        new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-token", issuedAt, issuedAt.plusSeconds(300)),
        idToken);
  }

}
