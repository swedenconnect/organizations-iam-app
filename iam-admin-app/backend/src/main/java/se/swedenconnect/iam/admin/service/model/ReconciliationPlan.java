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
package se.swedenconnect.iam.admin.service.model;

import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.stream.Stream;

/**
 * The work a reconciliation run will carry out.
 *
 * @param ensure combinations whose artifacts must exist
 * @param remove combinations whose artifacts must be removed; empty unless pruning is enabled
 *
 * @author Felix Hellman
 */
public record ReconciliationPlan(
    @NonNull List<ReconciliationTarget> ensure,
    @NonNull List<ReconciliationTarget> remove) {

  /**
   * Returns the number of distinct clients the plan touches.
   *
   * @return the client count
   */
  public int clientCount() {
    return (int) Stream.concat(this.ensure.stream(), this.remove.stream())
        .map(ReconciliationTarget::clientUuid)
        .distinct()
        .count();
  }

  /**
   * Tells whether the plan has no work to do.
   *
   * @return {@code true} if both lists are empty
   */
  public boolean isEmpty() {
    return this.ensure.isEmpty() && this.remove.isEmpty();
  }
}
