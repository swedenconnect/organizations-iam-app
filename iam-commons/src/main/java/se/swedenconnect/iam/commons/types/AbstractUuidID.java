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

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Abstract base class for v4 UUID-based identifiers.
 *
 * @author Martin Lindström
 */
public abstract class AbstractUuidID implements Serializable {

  @Serial
  private static final long serialVersionUID = LibraryVersion.SERIAL_VERSION_UID;

  /** The ID value. */
  @JsonValue
  private final String value;

  /**
   * Constructor accepting a string value.
   *
   * @param uuid the UUID in string format
   * @throws IllegalArgumentException if the supplied string is not a valid version 4 UUID
   */
  public AbstractUuidID(@NonNull final String uuid) throws IllegalArgumentException {
    this.value = uuid;
    final UUID _uuid = UUID.fromString(Objects.requireNonNull(uuid, "uuid must not be null"));
    if (_uuid.version() != 4) {
      throw new IllegalArgumentException("Supplied UUID must be a version 4 UUID");
    }
  }

  /**
   * Gets the value of the ID.
   *
   * @return the value
   */
  @NonNull
  public String getValue() {
    return this.value;
  }

  /**
   * Gets the ID as a UUID.
   *
   * @return a {@link UUID}
   */
  @NonNull
  public UUID getUUID() {
    return UUID.fromString(this.value);
  }

  /**
   * Generates a string to be used as an ID value.
   *
   * @return an ID value
   */
  @NonNull
  public static String generateIdValue() {
    return UUID.randomUUID().toString();
  }

  /** {@inheritDoc} */
  @Override
  public int hashCode() {
    return Objects.hash(this.value);
  }

  /** {@inheritDoc} */
  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }
    if ((obj == null) || (this.getClass() != obj.getClass())) {
      return false;
    }
    if (!this.getClass().equals(obj.getClass())) {
      return false;
    }
    final AbstractUuidID other = (AbstractUuidID) obj;
    return Objects.equals(this.value, other.value);
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return this.value;
  }

}
