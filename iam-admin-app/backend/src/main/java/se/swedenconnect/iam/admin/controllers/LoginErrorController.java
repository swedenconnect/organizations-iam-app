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

import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.swedenconnect.iam.admin.auth.OrgRightsOidcUserService;

import java.util.Collections;
import java.util.Map;

/**
 * Exposes the last authentication error description stored in the HTTP session.
 *
 * <p>Called by the frontend login page after a failed login to retrieve a human-readable reason.
 * The session attribute is consumed (read and removed) on first access.</p>
 *
 * <p>Returns {@code {"description": "..."}} when a description is available, or an empty object
 * when none is present (e.g. after a standard login failure without a session-stored reason).</p>
 *
 * @author Martin Lindström
 */
@RestController
@RequestMapping("/api")
public class LoginErrorController {

  @GetMapping(value = "/auth-error", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, String> authError(final @Nullable HttpSession session) {
    if (session == null) {
      return Collections.emptyMap();
    }
    final String description = (String) session.getAttribute(OrgRightsOidcUserService.AUTH_ERROR_ATTR);
    if (description != null) {
      session.removeAttribute(OrgRightsOidcUserService.AUTH_ERROR_ATTR);
      return Map.of("description", description);
    }
    return Collections.emptyMap();
  }

}
