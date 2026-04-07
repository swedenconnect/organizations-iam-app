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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import se.swedenconnect.iam.commons.LibraryVersion;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a multilingual string where values are mapped to language tags. This class allows storing, retrieving, and
 * managing localized string values based on locale or language tags.
 *
 * @author Martin Lindström
 */
public class LocalizedString implements Serializable {

  @Serial
  private static final long serialVersionUID = LibraryVersion.SERIAL_VERSION_UID;

  /** The default language tag. */
  public static final String DEFAULT_LANGUAGE = "sv";

  /** Indicates "no language specified". */
  public static final String NO_LANG = "no-lang";

  /**
   * A mapping of language tags to their corresponding localized string values. The keys of the map represent language
   * tags adhering to the BCP 47 specification (e.g., "en", "sv", "sv-SE"). The values represent the localized text
   * associated with each language tag. This field is used to store and retrieve localized string values based on
   * specific languages or regions.
   */
  private final Map<String, String> valuesByLanguageTag; // "sv", "en", "sv-SE", ...

  /**
   * Constructs an empty {@code LocalizedString} instance. Initializes an internal map to store localized values using
   * language tags as keys.
   */
  public LocalizedString() {
    this.valuesByLanguageTag = new HashMap<>();
  }

  /**
   * Constructs a {@code LocalizedString} instance with an initial value that is associated with a special language tag
   * indicating no specific language.
   *
   * @param value the initial value for the localized string. It is associated with a default language tag
   *     indicating no specific language. Cannot be null.
   */
  public LocalizedString(@NonNull final String value) {
    this();
    this.valuesByLanguageTag.put(NO_LANG, value);
  }

  /**
   * Constructs a {@code LocalizedString} instance with initial values specified as a map where keys are language tags
   * and values are the corresponding localized strings.
   *
   * <p>This constructor is also used as the Jackson JSON deserializer: a JSON object such as
   * {@code {"sv": "Hej", "en": "Hello"}} is deserialized directly into the internal map.</p>
   *
   * @param valuesByLanguageTag a map containing initial localized string values by language tag. The keys represent
   *     language tags (e.g., "en", "fr-FR"), and the values are the localized string values associated with those tags.
   *     Cannot be null.
   */
  @JsonCreator
  public LocalizedString(@NonNull final Map<String, String> valuesByLanguageTag) {
    this();
    this.valuesByLanguageTag.putAll(Objects.requireNonNull(valuesByLanguageTag));
  }

  /**
   * Adds a localized value associated with the specified {@link Locale}. The value is stored using the language tag
   * representation of the given locale.
   *
   * @param locale the {@link Locale} representing the language and region for the localized value. Cannot be null.
   * @param value the localized value to associate with the given locale. Cannot be null.
   * @throws NullPointerException if either the locale or the value is null.
   */
  public void add(@NonNull final Locale locale, @NonNull final String value) {
    this.valuesByLanguageTag.put(Objects.requireNonNull(locale).toLanguageTag(), Objects.requireNonNull(value));
  }

  /**
   * Adds a localized value associated with the specified BCP 47 language tag. The provided value is stored in an
   * internal map, with the language tag serving as the key.
   *
   * @param langTag the BCP 47 language tag (e.g., "en", "sv-SE") that represents the language and region of the
   *     localized value. Cannot be null.
   * @param value the localized value to store, associated with the specified language tag. Cannot be null.
   * @throws NullPointerException if either the {@code langTag} or {@code value} is null.
   */
  public void add(@NonNull final String langTag, @NonNull final String value) {
    this.valuesByLanguageTag.put(Objects.requireNonNull(langTag), Objects.requireNonNull(value));
  }

  /**
   * Adds a value associated with a special language tag indicating no specific language. The value is stored using a
   * predefined "no language" key in the internal map.
   *
   * @param value the value to be stored. Cannot be null.
   * @throws NullPointerException if the {@code value} is null.
   */
  public void add(@NonNull final String value) {
    this.valuesByLanguageTag.put(NO_LANG, Objects.requireNonNull(value));
  }

  /**
   * Adds a value from a claim, interpreting the claim name as a potential language-tagged key.
   * <p></p>
   * The method extracts a language tag from the claim name, if present, and normalizes it using the BCP 47 language tag
   * standard. If the claim name does not include a valid language tag (or no tag at all), the value is stored under a
   * special "no language" key.
   * </p>
   * <p>
   * Expected claim naming convention: {@code <base>#<langTag>}, e.g. {@code }organization_name#sv} or
   * {@code organization_name#sv-SE}. </p
   *
   * @param claimName the name of the claim, potentially containing a base name followed by a hashtag and a language
   *     tag. Cannot be null.
   * @param claimValue the value associated with the claim, which will be stored either using a normalized language
   *     tag extracted from the claim name or under a "no language" key if no valid tag is found. Cannot be null.
   * @throws NullPointerException if {@code claimName} or {@code claimValue} is null.
   */
  public void addFromClaim(@NonNull final String claimName, @NonNull final String claimValue) {
    Objects.requireNonNull(claimName, "claimName must not be null");
    Objects.requireNonNull(claimValue, "claimValue must not be null");

    // Expected claim naming convention: <base>#<langTag>, e.g. organization_name#sv or organization_name#sv-SE.
    // If no language tag is present, store under NO_LANG.
    final int idx = claimName.lastIndexOf('#');
    if (idx < 0 || idx == claimName.length() - 1) {
      this.add(claimValue);
      return;
    }

    final String rawTag = claimName.substring(idx + 1).trim();
    if (!StringUtils.hasText(rawTag)) {
      this.add(claimValue);
      return;
    }

    // Normalize language tag if possible.
    final Locale locale = Locale.forLanguageTag(rawTag);
    final String normalisedTag = StringUtils.hasText(locale.getLanguage()) ? locale.toLanguageTag() : rawTag;

    this.add(normalisedTag, claimValue);
  }

  /**
   * Retrieves a localized value associated with the specified {@link Locale}. The method searches for the most relevant
   * match based on the language tag derived from the given locale in the following priority order:
   * <ol>
   * <li>An exact match of the full language tag (e.g., "sv-SE").</li>
   * <li>A match for the language only (e.g., "sv").</li>
   * <li>A value associated with a special tag indicating no specific language.</li>
   * <li>A default value associated with a predefined default language tag.</li>
   * <li>Any available value if none of the above matches are found.</li>
   * </
   *
   * @param locale the {@link Locale} representing the desired language and region to retrieve the value for. Cannot
   *     be null.
   * @return the localized value associated with the most relevant match or {@code null} if no value is found.
   */
  @Nullable
  public String get(@NonNull final Locale locale) {
    final String tag = locale.toLanguageTag();     // e.g. "sv-SE"
    final String lang = locale.getLanguage();      // e.g. "sv"

    // Prefer the exact tag, then fall back to language, then no lang, then the default language, then any.
    return Optional.ofNullable(this.valuesByLanguageTag.get(tag))
        .or(() -> Optional.ofNullable(this.valuesByLanguageTag.get(lang)))
        .or(() -> Optional.ofNullable(this.valuesByLanguageTag.get(NO_LANG)))
        .or(() -> Optional.ofNullable(this.valuesByLanguageTag.get(DEFAULT_LANGUAGE)))
        .or(() -> this.valuesByLanguageTag.values().stream().findFirst())
        .orElse(null);
  }

  /**
   * Retrieves a localized value associated with the specified BCP 47 language tag. The method attempts to find the most
   * appropriate match in the following order:
   * <ol>
   * <li>A value corresponding to the given language tag.</li>
   * <li>A value associated with a special tag indicating no specific language.</li>
   * <li>A default value tied to a predefined default language tag.</li>
   * <li>Any available value if none of the above matches are found.</li>
   * </ol>
   *
   * @param langTag the BCP 47 language tag (e.g., "en", "fr-FR") representing the desired language and region to
   *     retrieve the value for. May be {@code null} or empty.
   * @return the localized value associated with the best match, or {@code null} if no value is found.
   */
  @Nullable
  public String get(@Nullable final String langTag) {
    if (!StringUtils.hasText(langTag)) {
      return Optional.ofNullable(this.valuesByLanguageTag.get(NO_LANG))
          .or(() -> Optional.ofNullable(this.valuesByLanguageTag.get(DEFAULT_LANGUAGE)))
          .or(() -> this.valuesByLanguageTag.values().stream().findFirst())
          .orElse(null);
    }
    else {
      return this.get(Locale.forLanguageTag(langTag));
    }
  }

  /**
   * Returns a map representation of the localized string values, where each key is a language tag and
   * the corresponding value is the localized string for that language.
   *
   * <p>This method is also used as the Jackson JSON serializer: a {@code LocalizedString} is
   * serialized as a plain JSON object, e.g. {@code {"sv": "Hej", "en": "Hello"}}.</p>
   *
   * @return a non-null Map containing localized string values, with language tags as keys.
   */
  @JsonValue
  @NonNull
  public Map<String, String> asMap() {
    return this.valuesByLanguageTag;
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return this.valuesByLanguageTag.toString();
  }

}
