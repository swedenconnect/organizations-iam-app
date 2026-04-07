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

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import se.swedenconnect.iam.commons.LibraryVersion;

import java.io.Serial;
import java.util.Optional;
import java.util.UUID;

/**
 * Representation of an Operation ID.
 *
 * @author Martin Lindström
 */
@Slf4j
public class OperationID extends AbstractUuidID implements MdcObject {

  @Serial
  private static final long serialVersionUID = LibraryVersion.SERIAL_VERSION_UID;

  /** The name for the OperationID when used as a field in JSON objects and similar. */
  public static final String FIELD_NAME = "operation_id";

  /** The name for the OperationID header when sent over HTTP */
  public static final String X_HEADER_NAME = "x-operation-id";

  /**
   * Constructor accepting a string value.
   *
   * @param uuid the UUID in string format
   * @throws IllegalArgumentException if the supplied string is not a valid version 4 UUID
   */
  public OperationID(@NonNull final String uuid) throws IllegalArgumentException {
    super(uuid);
  }

  /**
   * Creates an {@link OperationID} by reading the value from MDC.
   *
   * @return an {@link OperationID} or {@code null} if no value is available in MDC
   */
  @Nullable
  public static OperationID fromMDC() {
    try {
      return Optional.ofNullable(MDC.get(X_HEADER_NAME))
          .map(OperationID::new)
          .orElse(null);
    }
    catch (final Exception e) {
      log.warn("Error querying MDC for OperationID", e);
      return null;
    }
  }

  /** {@inheritDoc} */
  @Override
  public void mdcPut() {
    try {
      MDC.put(X_HEADER_NAME, this.getValue());
    }
    catch (final Exception e) {
      throw new RuntimeException("Failed to add OperationID to MDC", e);
    }
  }

  /**
   * Creates an {@link OperationID} from the given {@link UUID}.
   *
   * @param uuid the UUID
   * @return an {@link OperationID}
   * @throws IllegalArgumentException if the supplied UUID is not a version 4 UUID
   */
  @NonNull
  public static OperationID fromUUID(@NonNull final UUID uuid) throws IllegalArgumentException {
    return new OperationID(uuid.toString());
  }

  /**
   * Generates an {@link OperationID}.
   *
   * @return an {@link OperationID}
   */
  @NonNull
  public static OperationID generate() {
    return new OperationID(generateIdValue());
  }

}
