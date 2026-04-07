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
import org.springframework.util.StringUtils;
import se.swedenconnect.iam.commons.LibraryVersion;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Representation of a client's ID.
 *
 * @author Martin Lindström
 */
public class ClientID implements Serializable {

  @Serial
  private static final long serialVersionUID = LibraryVersion.SERIAL_VERSION_UID;

  /** The name for the ClientID when used as a field in JSON objects and similar. */
  public static final String FIELD_NAME = "client_id";

  /** The client ID. */
  @JsonValue
  private final String id;

  /**
   * Constructor.
   *
   * @param id the client ID
   */
  public ClientID(@NonNull final String id) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    if (!StringUtils.hasText(id)) {
      throw new IllegalArgumentException("id must not be empty");
    }
  }

  /**
   * Creates a new {@link ClientID}.
   *
   * @param id the client ID
   * @return a {@link ClientID}
   */
  public static ClientID of(@NonNull final String id) {
    return new ClientID(id);
  }

  /**
   * Gets the client ID.
   *
   * @return the ID
   */
  @NonNull
  public String getId() {
    return this.id;
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return this.id;
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
    final ClientID cardID = (ClientID) o;
    return Objects.equals(this.id, cardID.id);
  }

  /** {@inheritDoc} */
  @Override
  public int hashCode() {
    return Objects.hashCode(this.id);
  }

}
