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

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Exposes theme metadata and footer content so the frontend can adapt its appearance
 * to the active white-label profile ({@code iam.admin.theme}).
 *
 * <p>Both endpoints are publicly accessible — they are called by {@code theme-init.js}
 * before the user logs in.</p>
 *
 * @author Martin Lindström
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ThemeController {

  private static final Logger log = LoggerFactory.getLogger(ThemeController.class);

  private final IamAdminProperties properties;
  private final ResourceLoader resourceLoader;
  private final ObjectMapper objectMapper;

  /**
   * Returns theme metadata: active mode name, logo/favicon URLs, and logo heights.
   *
   * @return theme response
   */
  @GetMapping(value = "/theme", produces = MediaType.APPLICATION_JSON_VALUE)
  public ThemeResponse theme() {
    final FooterJson footer = loadFooterJson();
    return new ThemeResponse(
        this.properties.getTheme(),
        "/theme/logo.png",
        "/theme/footer-logo.png",
        "/theme/favicon.ico",
        footer.logoHeight() != null ? footer.logoHeight() : "h-12",
        footer.footerLogoHeight() != null ? footer.footerLogoHeight() : "h-8"
    );
  }

  /**
   * Returns footer content for the active theme, resolved to the requested locale.
   *
   * @param lang BCP 47 language tag, e.g. {@code sv} or {@code en}; defaults to {@code sv}
   * @return footer response
   */
  @GetMapping(value = "/theme/footer", produces = MediaType.APPLICATION_JSON_VALUE)
  public ThemeFooterResponse themeFooter(
      @RequestParam(name = "lang", defaultValue = "sv") final String lang) {
    final FooterJson footer = loadFooterJson();
    return new ThemeFooterResponse(
        resolve(footer.orgName(), lang),
        footer.contactEmail() != null ? footer.contactEmail() : "",
        footer.contactPhone() != null ? footer.contactPhone() : "",
        footer.links() != null
            ? footer.links().stream()
                .map(l -> new ThemeFooterLink(resolve(l.label(), lang), l.url()))
                .toList()
            : Collections.emptyList()
    );
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private FooterJson loadFooterJson() {
    final String themeDir = this.properties.getThemeDir();
    final String basePath = (themeDir != null && !themeDir.isBlank())
        ? "file:" + themeDir + "/"
        : "classpath:/static/theme/" + this.properties.getTheme() + "/";
    final Resource resource = this.resourceLoader.getResource(basePath + "footer.json");
    if (!resource.exists()) {
      log.warn("footer.json not found at '{}'; using empty defaults", basePath);
      return new FooterJson(null, null, null, null, null, null);
    }
    try {
      return this.objectMapper.readValue(resource.getInputStream(), FooterJson.class);
    }
    catch (final JacksonException e) {
      log.warn("Failed to parse footer.json at '{}': {}; using empty defaults", basePath, e.getMessage());
      return new FooterJson(null, null, null, null, null, null);
    }
    catch (final IOException e) {
      log.warn("Failed to read footer.json at '{}': {}; using empty defaults", basePath, e.getMessage());
      return new FooterJson(null, null, null, null, null, null);
    }
  }

  /**
   * Resolves a localized string map to a single value for the requested language,
   * falling back to the first available entry if the exact locale is absent.
   */
  private static String resolve(final Map<String, String> localized, final String lang) {
    if (localized == null || localized.isEmpty()) {
      return "";
    }
    final String value = localized.get(lang);
    if (value != null) {
      return value;
    }
    return localized.values().iterator().next();
  }

  // ---------------------------------------------------------------------------
  // Internal data model
  // ---------------------------------------------------------------------------

  record FooterJson(
      Map<String, String> orgName,
      String contactEmail,
      String contactPhone,
      List<FooterJsonLink> links,
      String logoHeight,
      String footerLogoHeight) {
  }

  record FooterJsonLink(
      Map<String, String> label,
      String url) {
  }

  // ---------------------------------------------------------------------------
  // Response records
  // ---------------------------------------------------------------------------

  record ThemeResponse(
      String mode,
      String logoUrl,
      String footerLogoUrl,
      String faviconUrl,
      String logoHeight,
      String footerLogoHeight) {
  }

  record ThemeFooterResponse(
      String orgName,
      String contactEmail,
      String contactPhone,
      List<ThemeFooterLink> links) {
  }

  record ThemeFooterLink(
      String label,
      String url) {
  }

}
