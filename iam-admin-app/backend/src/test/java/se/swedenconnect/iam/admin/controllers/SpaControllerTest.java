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
package se.swedenconnect.iam.admin.controllers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SpaController} — verifies that {@code <base href>} is injected correctly
 * based on the servlet context path read at request time.
 *
 * @author David Goldring
 */
@ExtendWith(MockitoExtension.class)
class SpaControllerTest {

  private static final String INDEX_WITH_BASE =
      "<!doctype html><html><head><base href=\"/\" /><title>IAM Admin</title></head>" +
          "<body><div id=\"root\"></div></body></html>";

  private static final String INDEX_WITHOUT_BASE =
      "<!doctype html><html><head><title>IAM Admin</title></head>" +
          "<body><div id=\"root\"></div></body></html>";

  @Mock
  private ResourceLoader resourceLoader;

  private SpaController spaController;

  @BeforeEach
  void setUp() {
    this.spaController = new SpaController(this.resourceLoader);
  }

  @Test
  void withContextPath_replacesBaseHref() throws IOException {
    givenIndexHtml(INDEX_WITH_BASE);

    final ResponseEntity<String> response = this.spaController.serveIndex(requestWithContextPath("/iam-admin"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
    assertThat(response.getBody()).contains("<base href=\"/iam-admin/\"");
    assertThat(response.getBody()).doesNotContain("<base href=\"/\"");
  }

  @Test
  void withoutContextPath_keepsRootBaseHref() throws IOException {
    givenIndexHtml(INDEX_WITH_BASE);

    final ResponseEntity<String> response = this.spaController.serveIndex(requestWithContextPath(""));

    assertThat(response.getBody()).contains("<base href=\"/\"");
  }

  @Test
  void withoutBaseTag_injectsBaseAfterHead() throws IOException {
    givenIndexHtml(INDEX_WITHOUT_BASE);

    final ResponseEntity<String> response = this.spaController.serveIndex(requestWithContextPath("/iam-admin"));

    assertThat(response.getBody()).contains("<head><base href=\"/iam-admin/\">");
    assertThat(response.getBody()).doesNotContain("<base href=\"/\"");
  }

  @Test
  void cacheControlHeaderPreventsClientCaching() throws IOException {
    givenIndexHtml(INDEX_WITH_BASE);

    final ResponseEntity<String> response = this.spaController.serveIndex(requestWithContextPath("/iam-admin"));

    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache, no-store, must-revalidate");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void givenIndexHtml(final String html) {
    when(this.resourceLoader.getResource("classpath:/static/index.html"))
        .thenReturn(new ByteArrayResource(html.getBytes(StandardCharsets.UTF_8)));
  }

  private static MockHttpServletRequest requestWithContextPath(final String contextPath) {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContextPath(contextPath);
    return request;
  }

}
