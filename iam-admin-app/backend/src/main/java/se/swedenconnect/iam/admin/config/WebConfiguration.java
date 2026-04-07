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

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuration for Web MVC.
 *
 * @author Martin Lindström
 */
@Configuration
@RequiredArgsConstructor
public class WebConfiguration implements WebMvcConfigurer {

  private final Environment environment;

  /**
   * Adds resource handlers for static assets and theme files.
   *
   * @param registry the registry to modify
   */
  @Override
  public void addResourceHandlers(final ResourceHandlerRegistry registry) {
    final String theme = this.environment.getProperty("iam.admin.theme", "digg");
    final String themeDir = this.environment.getProperty("iam.admin.theme-dir");
    final String themeLocation = (themeDir != null && !themeDir.isBlank())
        ? "file:" + themeDir + "/"
        : "classpath:/static/theme/" + theme + "/";

    registry.addResourceHandler("/assets/**")
        .addResourceLocations("classpath:/static/assets/");
    registry.addResourceHandler("/theme/**")
        .addResourceLocations(themeLocation);
  }

}
