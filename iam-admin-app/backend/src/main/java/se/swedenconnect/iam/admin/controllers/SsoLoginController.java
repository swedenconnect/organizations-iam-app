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
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import se.swedenconnect.iam.admin.auth.OrgRightsOidcUserService;

/**
 * Handles the SSO login entry point.
 *
 * <p>An external application redirects the user to this endpoint (configurable via
 * {@code app.sso-login-path}, defaulting to {@code /sso/login}) with optional {@code org} and
 * {@code func} query parameters specifying which organization and function the user must have
 * admin rights for.</p>
 *
 * <p>The controller stores the parameters in the HTTP session and then redirects to the standard
 * OAuth2 authorization endpoint. The presence of {@code sso.login.active} in the session signals
 * to {@link se.swedenconnect.iam.admin.auth.SsoAuthorizationRequestResolver} that {@code prompt=login}
 * should be omitted, allowing KeyCloak to reuse an existing SSO session.</p>
 *
 * @author Martin Lindström
 */
@Controller
@Slf4j
public class SsoLoginController {

  @GetMapping("${iam.admin.sso-login-path:/sso/login}")
  public String ssoLogin(
      @RequestParam(name = "org", required = false) final @Nullable String org,
      @RequestParam(name = "func", required = false) final @Nullable String func,
      final HttpSession session) {

    session.setAttribute(OrgRightsOidcUserService.SSO_ACTIVE_ATTR, Boolean.TRUE);
    session.setAttribute(OrgRightsOidcUserService.SSO_ORG_ATTR, org);
    session.setAttribute(OrgRightsOidcUserService.SSO_FUNC_ATTR, func);

    log.debug("SSO login initiated: org={}, func={}", org, func);

    return "redirect:/oauth2/authorization/iam-admin";
  }

}
