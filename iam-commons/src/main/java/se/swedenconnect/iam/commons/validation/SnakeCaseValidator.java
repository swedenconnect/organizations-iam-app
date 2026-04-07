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
package se.swedenconnect.iam.commons.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.jspecify.annotations.NonNull;

import java.util.regex.Pattern;

/**
 * Validates that a given string is in snake-case.
 *
 * @author Martin Lindström
 */
public class SnakeCaseValidator implements ConstraintValidator<SnakeCase, Object> {

  /** Regexp pattern for checking that the name is in snake-case. */
  private static final Pattern SNAKE_CASE_PATTERN = Pattern.compile("^[a-z]+(_[a-z0-9]+)*$");

  @Override
  public boolean isValid(final Object value, final ConstraintValidatorContext context) {
    if (value instanceof final String svalue) {
      return isSnakeCase(svalue);
    }
    else {
      return true;
    }
  }

  /**
   * Predicate that tells whether the supplied string is in snake-case (i.e., only lowercase with '_' as delimiter).
   *
   * @param value the value to check
   * @return {@code true} if snake-case and {@code false} otherwise
   */
  public static boolean isSnakeCase(@NonNull final String value) {
    try {
      return SNAKE_CASE_PATTERN.matcher(value).matches();
    }
    catch (final Exception e) {
      return false;
    }
  }

}
