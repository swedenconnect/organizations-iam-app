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
package se.swedenconnect.iam.admin.service;

import org.jspecify.annotations.NonNull;

/**
 * Thrown when an uploaded import file is malformed, carries an unsupported
 * {@code schemaVersion}, or exceeds the size limits enforced before any per-entry validation is
 * attempted. Rejects the whole file — unlike a single entry's {@code error} outcome, which only
 * excludes that entry from the batch.
 *
 * @author PF Plars
 */
public class ImportValidationException extends RuntimeException {

  public ImportValidationException(final @NonNull String message) {
    super(message);
  }

  public ImportValidationException(final @NonNull String message, final @NonNull Throwable cause) {
    super(message, cause);
  }

}
