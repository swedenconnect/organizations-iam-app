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
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpSession;
import se.swedenconnect.iam.admin.auth.LoginRejection;
import se.swedenconnect.iam.admin.auth.OrgRightsOidcUserService;
import se.swedenconnect.iam.admin.i18n.SupportedLanguages;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link LoginErrorController}, including that every login rejection code resolves in every
 * supported language against the message bundles that ship with the application.
 *
 * @author Martin Lindström
 */
class LoginErrorControllerTest {

  private LoginErrorController controller;

  @BeforeEach
  void setUp() {
    this.controller = new LoginErrorController(messageSource());
  }

  /** Mirrors the {@code spring.messages} configuration in {@code application.yml}. */
  private static MessageSource messageSource() {
    final ResourceBundleMessageSource source = new ResourceBundleMessageSource();
    source.setBasename("messages");
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(false);
    return source;
  }

  @Test
  void noSession_returnsEmptyResult() {
    assertThat(this.controller.authError(null)).isEmpty();
  }

  @Test
  void nothingStored_returnsEmptyResult() {
    assertThat(this.controller.authError(new MockHttpSession())).isEmpty();
  }

  @Test
  void storedRejection_isConsumedOnFirstRead() {
    final MockHttpSession session = sessionWith(LoginRejection.noOrganizationalRights());

    assertThat(this.controller.authError(session)).isNotEmpty();
    assertThat(this.controller.authError(session)).isEmpty();
  }

  @Test
  void response_carriesCodeAndOneMessagePerSupportedLanguage() {
    final Map<String, Object> response =
        this.controller.authError(sessionWith(LoginRejection.noOrganizationalRights()));

    assertThat(response.get("code")).isEqualTo(LoginRejection.NO_ORGANIZATIONAL_RIGHTS);
    assertThat(messagesOf(response)).containsOnlyKeys("sv", "en");
    assertThat(messagesOf(response).get("sv")).isEqualTo(
        "Du har inga organisatoriska rättigheter. Kontakta din systemadministratör.");
    assertThat(messagesOf(response).get("en")).isEqualTo(
        "You have no organizational rights. Please contact your system administrator.");
  }

  @Test
  void organizationConstrained_namesTheOrganization() {
    final Map<String, String> messages = messagesOf(this.controller.authError(
        sessionWith(LoginRejection.insufficientAdminRights("2021006883", null))));

    assertThat(messages.get("sv")).contains("2021006883").doesNotContain("{0}");
    assertThat(messages.get("en")).contains("2021006883").doesNotContain("{0}");
  }

  @Test
  void functionConstrained_namesTheFunction() {
    final Map<String, String> messages = messagesOf(this.controller.authError(
        sessionWith(LoginRejection.insufficientAdminRights(null, "walletreg"))));

    assertThat(messages.get("sv")).contains("walletreg").doesNotContain("{0}");
    assertThat(messages.get("en")).contains("walletreg").doesNotContain("{0}");
  }

  @Test
  void bothConstrained_namesTheFunctionAndTheOrganization() {
    final Map<String, String> messages = messagesOf(this.controller.authError(
        sessionWith(LoginRejection.insufficientAdminRights("2021006883", "walletreg"))));

    assertThat(messages.get("sv")).contains("walletreg").contains("2021006883");
    assertThat(messages.get("en")).contains("walletreg").contains("2021006883");
  }

  @Test
  void everyCodeResolvesInEverySupportedLanguage() {
    final MessageSource source = messageSource();
    final List<String> codes = declaredCodes();

    assertThat(codes).hasSize(5);
    for (final String code : codes) {
      for (final Locale locale : SupportedLanguages.LOCALES) {
        final String message = source.getMessage(code, new Object[] { "arg0", "arg1" }, locale);
        assertThat(message)
            .as("%s in %s", code, locale.getLanguage())
            .isNotBlank()
            .isNotEqualTo(code);
      }
    }
  }

  @Test
  void theTwoLanguagesDifferForEveryCode() {
    final MessageSource source = messageSource();

    for (final String code : declaredCodes()) {
      final String sv = source.getMessage(code, new Object[] { "arg0", "arg1" }, SupportedLanguages.SWEDISH);
      final String en = source.getMessage(code, new Object[] { "arg0", "arg1" }, SupportedLanguages.ENGLISH);
      assertThat(sv).as("%s is not translated", code).isNotEqualTo(en);
    }
  }

  /** All message codes declared by {@link LoginRejection}, so a new one without texts fails here. */
  private static List<String> declaredCodes() {
    final List<String> codes = new ArrayList<>();
    for (final Field field : LoginRejection.class.getDeclaredFields()) {
      if (Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers())
          && field.getType() == String.class) {
        try {
          codes.add((String) field.get(null));
        }
        catch (final IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    return codes;
  }

  private static MockHttpSession sessionWith(final LoginRejection rejection) {
    final MockHttpSession session = new MockHttpSession();
    session.setAttribute(OrgRightsOidcUserService.AUTH_ERROR_ATTR, rejection);
    return session;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> messagesOf(final Map<String, Object> response) {
    return (Map<String, String>) response.get("messages");
  }

}
