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

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Controller that serves the SPA {@code index.html} for all client-side routes,
 * injecting a {@code <base href="...">} tag so the frontend resolves relative
 * asset and API URLs correctly under any configured context path.
 *
 * @author Martin Lindström
 */
@Controller
@RequiredArgsConstructor
public class SpaController {

  private final ResourceLoader resourceLoader;

  @RequestMapping({
      "/",
      "/index.html",
      "/{path:^(?!api|assets|theme|actuator|error)[^.]*}",
      "/{path:^(?!api|assets|theme|actuator|error)[^.]*}/**"
  })
  @ResponseBody
  public @NonNull ResponseEntity<String> serveIndex(final HttpServletRequest request) throws IOException {
    final Resource resource = this.resourceLoader.getResource("classpath:/static/index.html");
    String html = resource.getContentAsString(StandardCharsets.UTF_8);
    final String contextPath = request.getContextPath();
    final String base = contextPath.isEmpty() ? "/" : contextPath + "/";
    // Replace any existing <base href="..."> regardless of what Vite wrote
    if (html.contains("<base ")) {
      html = html.replaceAll("<base\\s+href=\"[^\"]*\"", "<base href=\"" + base + "\"");
    }
    else {
      // Inject one if absent (safety net)
      html = html.replace("<head>", "<head><base href=\"" + base + "\">");
    }
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
        .body(html);
  }

}
