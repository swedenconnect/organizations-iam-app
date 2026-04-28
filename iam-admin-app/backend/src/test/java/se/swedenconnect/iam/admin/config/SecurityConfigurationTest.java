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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the unauthenticated-request handling in {@link SecurityConfiguration}.
 *
 * <p>The entry point distinguishes between API requests (which the SPA calls via {@code fetch})
 * and browser navigation. API requests must receive {@code 401 JSON} so the SPA can detect a
 * session expiry and redirect the user to the login page. Browser navigation should be redirected
 * to the OAuth2 authorization endpoint as usual.</p>
 *
 * @author David Goldring
 */
class SecurityConfigurationTest {

  private SecurityConfiguration config;

  @BeforeEach
  void setUp() {
    this.config = new SecurityConfiguration();
    ReflectionTestUtils.setField(this.config, "contextPath", "/");
  }

  @Test
  void apiRequest_returnsUnauthorizedWithJsonBody() throws IOException {
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
    request.setServletPath("/api/me");
    final MockHttpServletResponse response = new MockHttpServletResponse();

    this.config.handleUnauthenticated(request, response,
        new InsufficientAuthenticationException("no session"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"session-expired\"}");
  }

  @Test
  void apiRequest_doesNotRedirect() throws IOException {
    final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/organizations");
    request.setServletPath("/api/organizations");
    final MockHttpServletResponse response = new MockHttpServletResponse();

    this.config.handleUnauthenticated(request, response,
        new InsufficientAuthenticationException("no session"));

    assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
  }

  @Test
  void nonApiRequest_redirectsToOAuth2Login() throws IOException {
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
    request.setServletPath("/");
    final MockHttpServletResponse response = new MockHttpServletResponse();

    this.config.handleUnauthenticated(request, response,
        new InsufficientAuthenticationException("no session"));

    assertThat(response.getStatus()).isEqualTo(302);
    assertThat(response.getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/oauth2/authorization/iam-admin");
  }

  @Test
  void nonApiRequest_withContextPath_redirectIncludesContextPath() throws IOException {
    ReflectionTestUtils.setField(this.config, "contextPath", "/iam-admin");
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/iam-admin/");
    request.setServletPath("/");
    final MockHttpServletResponse response = new MockHttpServletResponse();

    this.config.handleUnauthenticated(request, response,
        new InsufficientAuthenticationException("no session"));

    assertThat(response.getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/iam-admin/oauth2/authorization/iam-admin");
  }

  @Test
  void apiRequest_withContextPath_stillReturnsUnauthorized() throws IOException {
    ReflectionTestUtils.setField(this.config, "contextPath", "/iam-admin");
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/iam-admin/api/session");
    request.setServletPath("/api/session");
    final MockHttpServletResponse response = new MockHttpServletResponse();

    this.config.handleUnauthenticated(request, response,
        new InsufficientAuthenticationException("no session"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"session-expired\"}");
  }
}
