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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.NonNull;
import se.swedenconnect.iam.commons.LibraryVersion;
import se.swedenconnect.iam.commons.validation.SnakeCaseValidator;
import se.swedenconnect.iam.commons.validation.SwedishOrganizationNumberValidator;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Representation of an organization ID.
 *
 * @author Martin Lindström
 */
public class OrganizationID implements Serializable {

  @Serial
  private static final long serialVersionUID = LibraryVersion.SERIAL_VERSION_UID;

  /** The name for the OrganizationID when used as a field in JSON objects and similar. */
  public static final String FIELD_NAME = "organization_identifier";

  /** The ID value. */
  @JsonValue
  private final String value;

  /**
   * Constructs a new {@code OrganizationID} instance.
   *
   * @param value the organization ID value, not null
   * @throws IllegalArgumentException if {@code value} is not a valid Swedish organization number
   */
  @JsonCreator
  public OrganizationID(@NonNull final String value) throws IllegalArgumentException {
    if (!SwedishOrganizationNumberValidator.isValid(Objects.requireNonNull(value, "value must not be null"))) {
      throw new IllegalArgumentException("Invalid Organization Identifier - invalid format");
    }
    this.value = value;
  }

  /**
   * Creates a new {@link OrganizationID}.
   *
   * @param id the organization ID
   * @return a {@link OrganizationID}
   */
  public static OrganizationID of(@NonNull final String id) {
    return new OrganizationID(id);
  }

  /**
   * Gets the organization ID.
   *
   * @return the ID
   */
  @NonNull
  public String getId() {
    return this.value;
  }

  @Override
  public String toString() {
    return this.value;
  }

  @Override
  public boolean equals(final Object o) {
    if (o == null || this.getClass() != o.getClass()) {
      return false;
    }
    final OrganizationID that = (OrganizationID) o;
    return Objects.equals(this.value, that.value);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(this.value);
  }

}
