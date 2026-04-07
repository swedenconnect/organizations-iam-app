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
import jakarta.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import se.swedenconnect.iam.commons.LibraryVersion;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Representation of a generic correlation ID.
 *
 * @author Martin Lindström
 */
@Slf4j
public class CorrelationID implements Serializable, MdcObject {

  @Serial
  private static final long serialVersionUID = LibraryVersion.SERIAL_VERSION_UID;

  /** The name for the CorrelationID when used as a field in JSON objects and similar. */
  public static final String FIELD_NAME = "correlation_id";

  /** The key of the CorrelationID when stored in MDC */
  public static final String MDC_KEY = "traceId";

  /** The ID value. */
  @JsonValue
  private final String value;

  /**
   * Constructor.
   *
   * @param value the value of the ID
   */
  public CorrelationID(@NonNull final String value) {
    this.value = Objects.requireNonNull(value, "value must not be null");
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException("value must not be empty");
    }
  }

  /**
   * Creates an {@link CorrelationID} by reading the value from MDC.
   *
   * @return an {@link CorrelationID} or {@code null} if no value is available in MDC
   */
  @Nullable
  public static CorrelationID fromMDC() {
    try {
      return Optional.ofNullable(MDC.get(MDC_KEY))
          .map(CorrelationID::new)
          .orElse(null);
    }
    catch (final Exception e) {
      log.warn("Error querying MDC for CorrelationID", e);
      return null;
    }
  }

  /** {@inheritDoc} */
  @Override
  public void mdcPut() {
    try {
      MDC.put(MDC_KEY, this.getValue());
    }
    catch (final Exception e) {
      throw new RuntimeException("Failed to add CorrelationID to MDC", e);
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
   * Generates an {@link CorrelationID}.
   *
   * @return an {@link CorrelationID}
   */
  @NonNull
  public static CorrelationID generate() {
    return new CorrelationID(UUID.randomUUID().toString());
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
    final CorrelationID other = (CorrelationID) obj;
    return Objects.equals(this.value, other.value);
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return this.value;
  }

}
