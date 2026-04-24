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
import org.springframework.core.io.ResourceLoader;
import org.springframework.mock.web.MockHttpServletRequest;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ThemeController} — verifies that asset URLs include the context path
 * read at request time.
 *
 * @author David Goldring
 */
@ExtendWith(MockitoExtension.class)
class ThemeControllerTest {

  @Mock
  private IamAdminProperties properties;

  @Mock
  private ResourceLoader resourceLoader;

  @Mock
  private ObjectMapper objectMapper;

  private ThemeController themeController;

  @BeforeEach
  void setUp() {
    this.themeController = new ThemeController(this.properties, this.resourceLoader, this.objectMapper);
    when(this.properties.getTheme()).thenReturn("digg");
    when(this.properties.getThemeDir()).thenReturn(null);
    // Return a non-existent resource so loadFooterJson falls back to empty defaults
    final org.springframework.core.io.Resource absent = mock(org.springframework.core.io.Resource.class);
    when(absent.exists()).thenReturn(false);
    when(this.resourceLoader.getResource(anyString())).thenReturn(absent);
  }

  @Test
  void withContextPath_assetUrlsAreContextPathPrefixed() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContextPath("/iam-admin");

    final ThemeController.ThemeResponse response = this.themeController.theme(request);

    assertThat(response.logoUrl()).isEqualTo("/iam-admin/theme/logo.png");
    assertThat(response.footerLogoUrl()).isEqualTo("/iam-admin/theme/footer-logo.png");
    assertThat(response.faviconUrl()).isEqualTo("/iam-admin/theme/favicon.ico");
  }

  @Test
  void withoutContextPath_assetUrlsStartWithSlash() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContextPath("");

    final ThemeController.ThemeResponse response = this.themeController.theme(request);

    assertThat(response.logoUrl()).isEqualTo("/theme/logo.png");
    assertThat(response.footerLogoUrl()).isEqualTo("/theme/footer-logo.png");
    assertThat(response.faviconUrl()).isEqualTo("/theme/favicon.ico");
  }

}
