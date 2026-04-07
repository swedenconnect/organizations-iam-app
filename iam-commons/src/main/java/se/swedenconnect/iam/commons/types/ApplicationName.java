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

import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.NonNull;
import se.swedenconnect.iam.commons.LibraryVersion;
import se.swedenconnect.iam.commons.validation.SnakeCaseValidator;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Representation of an application name.
 */
public class ApplicationName implements Serializable {

  @Serial
  private static final long serialVersionUID = LibraryVersion.SERIAL_VERSION_UID;

  /** The name for the ApplicationName when used in JSON and audit entries. */
  public static final String FIELD_NAME = "application";

  /** The application name. */
  @JsonValue
  private final String name;

  /**
   * Constructor.
   *
   * @param name the application name
   * @throws IllegalArgumentException if the supplied name is not in snake case
   */
  public ApplicationName(@NonNull final String name) throws IllegalArgumentException {
    if (!SnakeCaseValidator.isSnakeCase(Objects.requireNonNull(name, "name must be set"))) {
      throw new IllegalArgumentException("Invalid application name - must be in snake-case");
    }
    this.name = name;
  }

  /**
   * Gets the application name.
   *
   * @return the application name
   */
  @NonNull
  public String getName() {
    return this.name;
  }

  /** {@inheritDoc} */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || this.getClass() != o.getClass()) {
      return false;
    }
    final ApplicationName that = (ApplicationName) o;
    return Objects.equals(this.name, that.name);
  }

  /** {@inheritDoc} */
  @Override
  public int hashCode() {
    return Objects.hashCode(this.name);
  }

  /** {@inheritDoc} */
  @NonNull
  public String toString() {
    return this.name;
  }

}
