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
 * Thrown when {@code POST /api/import/{batchId}/confirm} is called with a {@code batchId} that
 * does not match the batch held in the caller's session — because no dry-run was ever run in
 * this session, the batch has already been confirmed or cancelled, or it has expired.
 *
 * @author PF Plars
 */
public class ImportBatchNotFoundException extends RuntimeException {

  public ImportBatchNotFoundException(final @NonNull String message) {
    super(message);
  }

}
