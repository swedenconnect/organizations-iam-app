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
package se.swedenconnect.iam.demo.app.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Fallback controller for OAuth2 authorization code callbacks.
 * <p>
 * When {@code OAuth2AuthorizationCodeGrantFilter} processes a callback (with {@code code}
 * and {@code state} parameters), it exchanges the authorization code for tokens and then
 * redirects the browser to the callback URL <em>without</em> query parameters. This
 * controller catches that redirect and sends the browser back to the application root.
 * </p>
 *
 * @author Martin Lindström
 */
@Controller
public class OAuth2CallbackController {

  @GetMapping("/callback/oauth2/code/{registrationId}")
  public String callbackFallback() {
    return "redirect:/";
  }

}
