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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.swedenconnect.iam.admin.auth.LoginRejection;
import se.swedenconnect.iam.admin.auth.OrgRightsOidcUserService;
import se.swedenconnect.iam.admin.i18n.SupportedLanguages;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes the reason the most recent login was rejected, as stored in the HTTP session.
 *
 * <p>Called by the frontend login page after a failed login. The session attribute is consumed
 * (read and removed) on first access.</p>
 *
 * <p>Returns <pre>{"code": "...", "messages": {"sv": "...", "en": "..."}}</pre> when a rejection is
 * available, carrying the finished text in every supported language so the frontend can switch
 * language without asking again. Returns an empty object when nothing is stored, for example after a
 * standard login failure without a session-stored reason.</p>
 *
 * @author Martin Lindström
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class LoginErrorController {

  private final MessageSource messageSource;

  /**
   * Constructs a {@code LoginErrorController}.
   *
   * @param messageSource the application message source; must not be null
   */
  public LoginErrorController(final @NonNull MessageSource messageSource) {
    this.messageSource = messageSource;
  }

  /**
   * Consumes the stored login rejection and returns it as a code plus one finished message per
   * supported language.
   *
   * @param session the current session, or {@code null} if none exists
   * @return the rejection, or an empty map when nothing is stored; never null
   */
  @GetMapping(value = "/auth-error", produces = MediaType.APPLICATION_JSON_VALUE)
  public @NonNull Map<String, Object> authError(final @Nullable HttpSession session) {
    if (session == null) {
      return Collections.emptyMap();
    }
    final Object stored = session.getAttribute(OrgRightsOidcUserService.AUTH_ERROR_ATTR);
    if (!(stored instanceof final LoginRejection rejection)) {
      return Collections.emptyMap();
    }
    session.removeAttribute(OrgRightsOidcUserService.AUTH_ERROR_ATTR);

    final Object[] arguments = rejection.arguments().toArray();
    final Map<String, String> messages = new LinkedHashMap<>();
    SupportedLanguages.LOCALES.forEach(locale -> {
      try {
        messages.put(locale.getLanguage(),
            this.messageSource.getMessage(rejection.code(), arguments, locale));
      }
      catch (final NoSuchMessageException e) {
        log.warn("No '{}' message for login rejection code '{}'", locale.getLanguage(), rejection.code());
      }
    });

    final Map<String, Object> response = new LinkedHashMap<>();
    response.put("code", rejection.code());
    response.put("messages", messages);
    return response;
  }

}
