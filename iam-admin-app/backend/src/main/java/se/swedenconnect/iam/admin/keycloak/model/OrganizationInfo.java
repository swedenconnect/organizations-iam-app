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
package se.swedenconnect.iam.admin.keycloak.model;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import se.swedenconnect.iam.commons.types.LocalizedString;

import java.util.List;
import java.util.function.Predicate;

/**
 * Organization as represented in KeyCloak, with its attached functions.
 *
 * <p>An organization carries two distinct things. {@code legalName} is the name the organization is
 * registered under at Bolagsverket. It is a plain string with no language, it is mandatory, and it
 * is what identifies the organization. {@code displayName} holds optional Swedish and English names
 * whose only purpose is presentation; either or both may be absent, and the whole value is
 * {@code null} when no display name has been given at all.</p>
 *
 * <p>The legal name is deliberately not carried inside {@code displayName}: it is not a localized
 * value and must not be reachable through a language lookup on this model. Use
 * {@link #resolveName(String)} where a name is to be shown.</p>
 *
 * @author Martin Lindström
 */
public record OrganizationInfo(
    @NonNull String orgIdentifier,
    @NonNull String legalName,
    @Nullable LocalizedString displayName,
    @NonNull String groupId,
    @NonNull List<String> attachedFunctions,
    @Nullable String contactEmail,
    @Nullable String contactPhone) {

  /**
   * Returns a copy of this record with {@code attachedFunctions} filtered by the given predicate.
   *
   * @param functionFilter predicate that returns {@code true} for function identifiers to keep
   * @return a new {@code OrganizationInfo} with filtered attached functions
   */
  public @NonNull OrganizationInfo withFilteredFunctions(final @NonNull Predicate<String> functionFilter) {
    return new OrganizationInfo(
        this.orgIdentifier,
        this.legalName,
        this.displayName,
        this.groupId,
        this.attachedFunctions.stream().filter(functionFilter).toList(),
        this.contactEmail,
        this.contactPhone);
  }

  /**
   * Returns the display name for the given language, the display name in any other language if that
   * one is not set, and the legal name if no display name has been given at all.
   *
   * @param langTag the BCP 47 language tag to prefer, may be {@code null}
   * @return a name to show; never {@code null} and never empty
   */
  public @NonNull String resolveName(final @Nullable String langTag) {
    if (this.displayName != null) {
      final String display = this.displayName.get(langTag);
      if (display != null && !display.isBlank()) {
        return display;
      }
    }
    return this.legalName;
  }

  /**
   * Returns the display name for the given language only, without falling back to the legal name.
   *
   * @param langTag the BCP 47 language tag
   * @return the display name in that language, or {@code null} if it is not set
   */
  public @Nullable String displayName(final @NonNull String langTag) {
    return this.displayName != null ? this.displayName.asMap().get(langTag) : null;
  }

}
