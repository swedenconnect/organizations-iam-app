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

/**
 * A marker interface for objects that are stored in MDC (Mapped Diagnostic Context).
 * <p>
 * Note: It is recommended that every class that implements this interface supplied a static {@code fromMDC} method.
 * </p>
 *
 * @author Martin Lindström
 */
public interface MdcObject {

  /**
   * Adds the object to MDC.
   */
  void mdcPut();

}
