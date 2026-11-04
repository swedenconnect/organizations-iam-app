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
package se.swedenconnect.iam.admin.audit;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import se.swedenconnect.iam.admin.controllers.SsoLoginController;
import se.swedenconnect.spring.audit.tracing.CorrelationID;
import se.swedenconnect.spring.audit.tracing.CorrelationIDHolder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests how a session gets its correlation ID, and that the ID is installed for each request and
 * removed afterwards.
 *
 * @author Martin Lindström
 */
class CorrelationIdTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  private final SsoLoginController ssoLoginController = new SsoLoginController();

  @AfterEach
  void clear() {
    CorrelationIDHolder.clear();
  }

  @ParameterizedTest
  @ValueSource(strings = { "abc", "A-b_c.d:e", "0123456789" })
  void isValid_acceptedCharacters(final String value) {
    assertThat(SessionCorrelationId.isValid(value)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "a b", "a/b", "a\nb", "<script>", "åäö", "a%20b" })
  void isValid_rejectedCharacters(final String value) {
    assertThat(SessionCorrelationId.isValid(value)).isFalse();
  }

  @Test
  void isValid_atMost128Characters() {
    assertThat(SessionCorrelationId.isValid("a".repeat(128))).isTrue();
    assertThat(SessionCorrelationId.isValid("a".repeat(129))).isFalse();
    assertThat(SessionCorrelationId.isValid(null)).isFalse();
  }

  /** A valid value from the SSO endpoint becomes the session's correlation ID. */
  @Test
  void sso_validValue_isUsed() {
    final MockHttpSession session = new MockHttpSession();
    this.ssoLoginController.ssoLogin(null, null, "caller-flow-1", session);

    assertThat(SessionCorrelationId.get(session)).isEqualTo(CorrelationID.of("caller-flow-1"));
    assertThat(CorrelationIDHolder.get()).isEqualTo(CorrelationID.of("caller-flow-1"));
  }

  /** An invalid value does not stop the login, it is replaced by a generated one. */
  @Test
  void sso_invalidValue_isIgnored() {
    final MockHttpSession session = new MockHttpSession();
    final String view = this.ssoLoginController.ssoLogin("5565552014", null, "bad value\n", session);

    assertThat(view).isEqualTo("redirect:/oauth2/authorization/iam-admin");
    final CorrelationID id = SessionCorrelationId.get(session);
    assertThat(id).isNotNull();
    assertThat(id.getValue()).isNotEqualTo("bad value\n");
  }

  /** Without a value a new one is generated. */
  @Test
  void sso_noValue_generatesOne() {
    final MockHttpSession session = new MockHttpSession();
    this.ssoLoginController.ssoLogin(null, null, null, session);

    assertThat(SessionCorrelationId.get(session)).isNotNull();
  }

  /** A session that already has a correlation ID keeps it, and a supplied value is ignored. */
  @Test
  void sso_existingSession_keepsItsValue() {
    final MockHttpSession session = new MockHttpSession();
    session.setAttribute(SessionCorrelationId.SESSION_ATTR, "existing");

    this.ssoLoginController.ssoLogin(null, null, "new-value", session);

    assertThat(SessionCorrelationId.get(session)).isEqualTo(CorrelationID.of("existing"));
  }

  /** A standard login gets a new correlation ID when it starts, which is then kept for the session. */
  @Test
  void filter_standardLoginStart_assignsAndKeepsId() throws Exception {
    final MockHttpSession session = new MockHttpSession();
    final List<CorrelationID> seen = new ArrayList<>();
    final FilterChain chain = (req, res) -> seen.add(CorrelationIDHolder.get());

    this.filter.doFilter(request("/oauth2/authorization/iam-admin", session), new MockHttpServletResponse(), chain);
    this.filter.doFilter(request("/login/oauth2/code/iam-admin", session), new MockHttpServletResponse(), chain);
    this.filter.doFilter(request("/api/users", session), new MockHttpServletResponse(), chain);

    assertThat(seen).hasSize(3).doesNotContainNull();
    assertThat(seen).containsOnly(seen.getFirst());
    assertThat(SessionCorrelationId.get(session)).isEqualTo(seen.getFirst());
  }

  /** A login starting in a session that already has a correlation ID keeps it. */
  @Test
  void filter_loginStartInExistingSession_keepsId() throws Exception {
    final MockHttpSession session = new MockHttpSession();
    session.setAttribute(SessionCorrelationId.SESSION_ATTR, "existing");
    final List<CorrelationID> seen = new ArrayList<>();

    this.filter.doFilter(request("/oauth2/authorization/iam-admin", session), new MockHttpServletResponse(),
        (req, res) -> seen.add(CorrelationIDHolder.get()));

    assertThat(seen).containsExactly(CorrelationID.of("existing"));
  }

  /** The ID is removed when the request is done, and a stale value is never carried into a request. */
  @Test
  void filter_clearsIdAfterRequestAndIgnoresStaleValue() throws Exception {
    CorrelationIDHolder.set(CorrelationID.of("left-over"));
    final List<CorrelationID> seen = new ArrayList<>();

    this.filter.doFilter(request("/api/users", null), new MockHttpServletResponse(),
        (req, res) -> seen.add(CorrelationIDHolder.get()));

    assertThat(seen).containsExactly((CorrelationID) null);
    assertThat(CorrelationIDHolder.get()).isNull();
  }

  /** The ID is removed even when the request fails. */
  @Test
  void filter_clearsIdOnException() {
    final MockHttpSession session = new MockHttpSession();
    session.setAttribute(SessionCorrelationId.SESSION_ATTR, "existing");

    try {
      this.filter.doFilter(request("/api/users", session), new MockHttpServletResponse(), (req, res) -> {
        throw new IllegalStateException("boom");
      });
    }
    catch (final Exception e) {
      // expected
    }
    assertThat(CorrelationIDHolder.get()).isNull();
  }

  /** The IAM service API carries no correlation ID. */
  @Test
  void filter_iamApi_noId() throws Exception {
    final MockHttpSession session = new MockHttpSession();
    session.setAttribute(SessionCorrelationId.SESSION_ATTR, "existing");
    final List<CorrelationID> seen = new ArrayList<>();

    this.filter.doFilter(request("/iam-api/v1/organizations", session), new MockHttpServletResponse(),
        (req, res) -> seen.add(CorrelationIDHolder.get()));

    assertThat(seen).containsExactly((CorrelationID) null);
  }

  /** Two sessions get different IDs. */
  @Test
  void filter_differentSessions_differentIds() throws Exception {
    final MockHttpSession first = new MockHttpSession();
    final MockHttpSession second = new MockHttpSession();
    final FilterChain chain = (req, res) -> {
    };
    this.filter.doFilter(request("/oauth2/authorization/iam-admin", first), new MockHttpServletResponse(), chain);
    this.filter.doFilter(request("/oauth2/authorization/iam-admin", second), new MockHttpServletResponse(), chain);

    assertThat(SessionCorrelationId.get(first)).isNotEqualTo(SessionCorrelationId.get(second));
  }

  private static MockHttpServletRequest request(final String path, final MockHttpSession session) {
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);
    if (session != null) {
      request.setSession(session);
    }
    return request;
  }

}
