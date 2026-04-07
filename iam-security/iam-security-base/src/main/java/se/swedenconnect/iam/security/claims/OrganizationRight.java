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
package se.swedenconnect.iam.security.claims;

import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * An enum representing the rights a user can have on an organization.
 *
 * @author Martin Lindström
 */
public enum OrganizationRight {

  /** Read right, but not write or admin. */
  READ("read"),

  /** Write right. Also gives read, but not admin. */
  WRITE("write"),

  /** Full rights. */
  ADMIN("admin");

  /**
   * The string representation of the right associated with the organization. This value is used to identify and map the
   * specific organizational right (e.g., "read", "write", "admin") in a human-readable format.
   */
  private final String right;

  /**
   * Constructs an instance of {@code OrganizationRight} with the specified string representation of the organizational
   * right.
   *
   * @param right the string representation of the right associated with the organization; must not be null
   */
  OrganizationRight(final @NonNull String right) {
    this.right = right;
  }

  /**
   * Retrieves the string representation of the right associated with the organization.
   *
   * @return the string representation of the organizational right (e.g., "read", "write", "admin"); never null
   */
  public @NonNull String getRight() {
    return this.right;
  }

  /**
   * Parses a string representation of an organizational right and returns the corresponding {@code OrganizationRight}
   * enum constant.
   *
   * @param right the string representation of the right to parse; must not be null
   * @return the corresponding {@code OrganizationRight} enum constant; never null
   * @throws IllegalArgumentException if the provided string does not match any known organizational right
   */
  public static OrganizationRight parse(final @NonNull String right) throws IllegalArgumentException {
    Objects.requireNonNull(right);
    for (final OrganizationRight orgRight : OrganizationRight.values()) {
      if (orgRight.right.equalsIgnoreCase(right)) {
        return orgRight;
      }
    }
    throw new IllegalArgumentException(
        "Value '%s' could not be parsed into an OrganizationRight".formatted(right));
  }

}
