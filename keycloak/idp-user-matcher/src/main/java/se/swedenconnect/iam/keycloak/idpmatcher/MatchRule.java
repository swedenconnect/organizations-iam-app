/*
 * Copyright 2026 Sweden Connect
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.swedenconnect.iam.keycloak.idpmatcher;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.Constants;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One entry of the matcher's {@code matchAttribute} list: the user attribute to match on, and the
 * roles a user matched through it must and must not hold.
 *
 * <p>An entry is written as the attribute name, optionally followed by roles, separated by
 * {@code ;}:</p>
 * <pre>
 * personalIdentityNumber;forbiddenRole=superuser
 * efosId;requiredRole=superuser
 * </pre>
 *
 * @param attribute the user attribute to match on
 * @param requiredRole a realm role the matched user must hold, or {@code null}
 * @param forbiddenRole a realm role the matched user must not hold, or {@code null}
 *
 * @author Martin Lindström
 */
public record MatchRule(
    @NonNull String attribute,
    @Nullable String requiredRole,
    @Nullable String forbiddenRole) {

  /** Separates the attribute name and the role settings within an entry. */
  static final String ENTRY_SEPARATOR = ";";

  /** Separates a role setting's key from its value. */
  static final String VALUE_SEPARATOR = "=";

  /** The entry key for the role a matched user must hold. */
  static final String REQUIRED_ROLE_KEY = "requiredRole";

  /** The entry key for the role a matched user must not hold. */
  static final String FORBIDDEN_ROLE_KEY = "forbiddenRole";

  /**
   * Reads the ordered list of rules from the authenticator configuration.
   *
   * <p>The configuration fails closed: anything that could leave a role restriction unenforced, or
   * the list ambiguous, is an error rather than something to work around. A blank entry is neither,
   * so it is skipped. It is what the Admin Console stores for a row left empty.</p>
   *
   * @param config the authenticator configuration, may be {@code null} if the execution has none
   * @return the rules, in the configured order; never empty
   * @throws InvalidMatchConfigurationException if the configuration is missing or invalid
   */
  static @NonNull List<MatchRule> fromConfig(final @Nullable AuthenticatorConfigModel config) {
    final Map<String, String> values = config != null ? config.getConfig() : null;
    if (values == null) {
      throw new InvalidMatchConfigurationException("authenticator has no configuration");
    }
    for (final String removed : List.of(
        IdpDetectExistingUserByAttributeAuthenticatorFactory.REMOVED_REQUIRED_ROLE,
        IdpDetectExistingUserByAttributeAuthenticatorFactory.REMOVED_FORBIDDEN_ROLE)) {
      if (values.containsKey(removed)) {
        throw new InvalidMatchConfigurationException(
            "the removed setting '%s' is still present; move the role to an entry in '%s'"
                .formatted(removed, IdpDetectExistingUserByAttributeAuthenticatorFactory.MATCH_ATTRIBUTE));
      }
    }

    final String list = values.get(IdpDetectExistingUserByAttributeAuthenticatorFactory.MATCH_ATTRIBUTE);
    if (list == null || list.isEmpty()) {
      throw noEntries();
    }

    final List<MatchRule> rules = new ArrayList<>();
    final Set<String> attributes = new HashSet<>();
    // Split on the delimiter alone. Keycloak's own pattern also swallows spaces around it, which
    // would hide a malformed entry.
    final String[] entries = list.split(Pattern.quote(Constants.CFG_DELIMITER), -1);
    for (int i = 0; i < entries.length; i++) {
      if (entries[i].isBlank()) {
        // The position still counts the blank entry, so an error names the row the admin sees
        continue;
      }
      final MatchRule rule = parse(entries[i], i + 1);
      if (!attributes.add(rule.attribute())) {
        throw new InvalidMatchConfigurationException(
            "attribute '%s' appears more than once".formatted(rule.attribute()));
      }
      rules.add(rule);
    }
    if (rules.isEmpty()) {
      throw noEntries();
    }
    return List.copyOf(rules);
  }

  /**
   * Parses one entry of the list.
   *
   * @param entry the entry text
   * @param position the 1-based position of the entry, for the error message
   * @return the rule
   * @throws InvalidMatchConfigurationException if the entry is malformed
   */
  static @NonNull MatchRule parse(final @NonNull String entry, final int position) {
    final String[] parts = entry.split(ENTRY_SEPARATOR, -1);

    final String attribute = parts[0];
    if (attribute.isEmpty()) {
      throw malformed(position, "no attribute name");
    }
    if (containsWhitespace(attribute)) {
      throw malformed(position, "spaces in or around the attribute name");
    }

    String requiredRole = null;
    String forbiddenRole = null;
    for (int i = 1; i < parts.length; i++) {
      final String part = parts[i];
      final int eq = part.indexOf(VALUE_SEPARATOR);
      if (eq < 0) {
        throw malformed(position, "'%s' is not on the form key=role".formatted(part));
      }
      final String key = part.substring(0, eq);
      final String role = part.substring(eq + 1);
      if (containsWhitespace(key) || (!role.isEmpty() && !role.equals(role.strip()))) {
        throw malformed(position, "spaces around a separator");
      }
      if (role.isEmpty()) {
        throw malformed(position, "empty role for '%s'".formatted(key));
      }
      switch (key) {
        case REQUIRED_ROLE_KEY -> {
          if (requiredRole != null) {
            throw malformed(position, "'%s' given twice".formatted(key));
          }
          requiredRole = role;
        }
        case FORBIDDEN_ROLE_KEY -> {
          if (forbiddenRole != null) {
            throw malformed(position, "'%s' given twice".formatted(key));
          }
          forbiddenRole = role;
        }
        default -> throw malformed(position, "unknown key '%s'".formatted(key));
      }
    }
    return new MatchRule(attribute, requiredRole, forbiddenRole);
  }

  private static @NonNull InvalidMatchConfigurationException noEntries() {
    return new InvalidMatchConfigurationException("'%s' is missing or has no entries"
        .formatted(IdpDetectExistingUserByAttributeAuthenticatorFactory.MATCH_ATTRIBUTE));
  }

  private static boolean containsWhitespace(final @NonNull String value) {
    return value.chars().anyMatch(Character::isWhitespace);
  }

  private static @NonNull InvalidMatchConfigurationException malformed(final int position, final @NonNull String what) {
    return new InvalidMatchConfigurationException("entry %d of '%s' is malformed: %s".formatted(
        position, IdpDetectExistingUserByAttributeAuthenticatorFactory.MATCH_ATTRIBUTE, what));
  }

  /**
   * Thrown when the matcher configuration is missing or invalid. The message is the reason, for the
   * server log only.
   */
  static final class InvalidMatchConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     *
     * @param reason the reason, for the server log
     */
    InvalidMatchConfigurationException(final @NonNull String reason) {
      super(reason);
    }
  }
}
