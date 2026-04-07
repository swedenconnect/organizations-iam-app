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
package se.swedenconnect.iam.commons.types;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test cases for the {@link LocalizedString} class.
 *
 * @author Martin Lindström
 */
class LocalizedStringTest {

  /**
   * Test the {@code add(Locale, String)} method with a valid {@link Locale} and value. Verifies that the value is
   * stored correctly in the internal map using the language tag.
   */
  @Test
  void testAddWithLocaleAndValue() {
    final Locale locale = Locale.of("en", "US");
    final String value = "Hello";

    final LocalizedString localizedString = new LocalizedString();
    localizedString.add(locale, value);

    assertEquals("Hello", localizedString.asMap().get("en-US"));
  }

  /**
   * Test the {@code add(Locale, String)} method with a null {@link Locale}. Verifies that the method throws a
   * {@link NullPointerException}.
   */
  @Test
  void testAddWithNullLocale() {
    final String value = "Hello";

    final LocalizedString localizedString = new LocalizedString();

    assertThrows(NullPointerException.class, () -> localizedString.add((Locale) null, value));
  }

  /**
   * Test the {@code add(Locale, String)} method with a null value. Verifies that the method throws a
   * {@link NullPointerException}.
   */
  @Test
  void testAddWithNullValueAndLocale() {
    final Locale locale = Locale.of("en", "US");

    final LocalizedString localizedString = new LocalizedString();

    assertThrows(NullPointerException.class, () -> localizedString.add(locale, null));
  }

  /**
   * Test the {@code add(String, String)} method with a valid language tag and value. Verifies that the value is stored
   * correctly in the internal map using the language tag.
   */
  @Test
  void testAddWithLangTagAndValue() {
    final String langTag = "en-US";
    final String value = "Hello";

    final LocalizedString localizedString = new LocalizedString();
    localizedString.add(langTag, value);

    assertEquals("Hello", localizedString.asMap().get("en-US"));
  }

  /**
   * Test the {@code add(String, String)} method with a null language tag. Verifies that the method throws a
   * {@link NullPointerException}.
   */
  @Test
  void testAddWithNullLangTag() {
    final String value = "Hello";

    final LocalizedString localizedString = new LocalizedString();

    assertThrows(NullPointerException.class, () -> localizedString.add((String) null, value));
  }

  /**
   * Test the {@code add(String, String)} method with a null value. Verifies that the method throws a
   * {@link NullPointerException}.
   */
  @Test
  void testAddWithNullValueAndLangTag() {
    final String langTag = "en-US";

    final LocalizedString localizedString = new LocalizedString();

    assertThrows(NullPointerException.class, () -> localizedString.add(langTag, null));
  }

  /**
   * Test the {@code add(String)} method with a valid value. Verifies that the value is stored correctly using the
   * "no-lang" key.
   */
  @Test
  void testAddWithNoLangValue() {
    final String value = "Hej";

    final LocalizedString localizedString = new LocalizedString();
    localizedString.add(value);

    assertEquals("Hej", localizedString.asMap().get(LocalizedString.NO_LANG));
  }

  /**
   * Test the {@code add(String)} method with a null value. Verifies that the method throws a
   * {@link NullPointerException}.
   */
  @Test
  void testAddWithNullValueForNoLang() {
    final LocalizedString localizedString = new LocalizedString();

    assertThrows(NullPointerException.class, () -> localizedString.add(null));
  }

  /**
   * Test the {@code addFromClaim(String, String)} with a claim that contains a language tag. Verifies that the value is
   * stored with the appropriate language tag.
   */
  @Test
  void testAddFromClaim() {
    final String claimName = "organization_name#sv-SE";
    final String claimValue = "Hej";

    final LocalizedString localizedString = new LocalizedString();
    localizedString.addFromClaim(claimName, claimValue);

    assertEquals("Hej", localizedString.asMap().get("sv-SE"));
  }

  /**
   * Test the {@code addFromClaim(String, String)} with a claim that doesn't contain a language tag. Verifies that the
   * value is stored under the "no-lang" key.
   */
  @Test
  void testAddFromClaimNoLangTag() {
    final String claimName = "organization_name";
    final String claimValue = "Hey";

    final LocalizedString localizedString = new LocalizedString();
    localizedString.addFromClaim(claimName, claimValue);

    assertEquals("Hey", localizedString.asMap().get(LocalizedString.NO_LANG));
  }

  /**
   * Test the {@code addFromClaim(String, String)} with a null claim name. Verifies that the method throws a
   * {@link NullPointerException}.
   */
  @Test
  void testAddFromClaimWithNullClaimName() {
    final String claimValue = "Hello";

    final LocalizedString localizedString = new LocalizedString();

    assertThrows(NullPointerException.class, () -> localizedString.addFromClaim(null, claimValue));
  }

  /**
   * Test the {@code addFromClaim(String, String)} with a null claim value. Verifies that the method throws a
   * {@link NullPointerException}.
   */
  @Test
  void testAddFromClaimWithNullClaimValue() {
    final String claimName = "organization_name#en";

    final LocalizedString localizedString = new LocalizedString();

    assertThrows(NullPointerException.class, () -> localizedString.addFromClaim(claimName, null));
  }

  /**
   * Verifies that a {@code LocalizedString} serializes to a flat JSON object whose keys are language tags and whose
   * values are the localized strings — identical to serializing the underlying map directly.
   */
  @Test
  void testJsonSerialization() throws Exception {
    final LocalizedString ls = new LocalizedString();
    ls.add("sv", "Hej");
    ls.add("en", "Hello");

    final String json = new ObjectMapper().writeValueAsString(ls);

    // Must round-trip back to the same values
    @SuppressWarnings("unchecked")
    final java.util.Map<String, String> parsed =
        new ObjectMapper().readValue(json, java.util.Map.class);
    assertEquals("Hej", parsed.get("sv"));
    assertEquals("Hello", parsed.get("en"));
    assertEquals(2, parsed.size());
  }

  /**
   * Verifies that a flat JSON object is deserialized into a {@code LocalizedString} with the correct language-to-value
   * mappings, and that lookup via {@link LocalizedString#get(String)} works as expected.
   */
  @Test
  void testJsonDeserialization() throws Exception {
    final String json = "{\"sv\":\"Hej\",\"en\":\"Hello\"}";

    final LocalizedString ls = new ObjectMapper().readValue(json, LocalizedString.class);

    assertEquals("Hej", ls.get("sv"));
    assertEquals("Hello", ls.get("en"));
  }

  /**
   * Verifies that an empty JSON object {@code {}} deserializes to an empty {@code LocalizedString} that returns
   * {@code null} for any language lookup.
   */
  @Test
  void testJsonDeserializationEmpty() throws Exception {
    final LocalizedString ls = new ObjectMapper().readValue("{}", LocalizedString.class);

    assertNull(ls.get("sv"));
    assertNull(ls.get("en"));
  }
}
