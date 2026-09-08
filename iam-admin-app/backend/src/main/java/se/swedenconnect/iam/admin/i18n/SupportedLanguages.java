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
package se.swedenconnect.iam.admin.i18n;

import java.util.List;
import java.util.Locale;

/**
 * The languages the admin application produces user visible text in.
 *
 * <p>This is the single declaration of the supported language set. It is deliberately not a
 * configuration property: the set is bound to the message bundles shipped with the application
 * ({@code messages.properties} and {@code messages_sv.properties}) and to the languages the frontend
 * can display.</p>
 *
 * @author Martin Lindström
 */
public final class SupportedLanguages {

  /** Swedish, the default language of the application. */
  public static final Locale SWEDISH = Locale.of("sv");

  /** English, the language of the default message bundle. */
  public static final Locale ENGLISH = Locale.ENGLISH;

  /** All supported languages, in declaration order. Keyed elsewhere by {@link Locale#getLanguage()}. */
  public static final List<Locale> LOCALES = List.of(SWEDISH, ENGLISH);

  // Hidden constructor
  private SupportedLanguages() {
  }

}
