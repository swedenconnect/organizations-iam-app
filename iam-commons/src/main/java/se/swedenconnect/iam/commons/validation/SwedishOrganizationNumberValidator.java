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

/**
 * Validates that a given string is a valid Swedish organization number.
 *
 * @author Martin Lindström
 */
public class SwedishOrganizationNumberValidator implements ConstraintValidator<SwedishOrganizationNumber, Object> {

  @Override
  public boolean isValid(final Object o, final ConstraintValidatorContext constraintValidatorContext) {
    if (o instanceof final String value) {
      return isValid(value);
    }
    else {
      return false;
    }
  }

  public static boolean isValid(final String value) {
    if (value == null) {
      return false;
    }

    if (!value.matches("\\d{10}")) {
      return false;
    }

    // The third digit must be >= 2
    final int thirdDigit = value.charAt(2) - '0';
    if (thirdDigit < 2) {
      return false;
    }
    final int expectedCheckDigit = luhnMod10CheckDigit(value.substring(0, 9));
    final int actualCheckDigit = value.charAt(9) - '0';
    return expectedCheckDigit == actualCheckDigit;
  }

  /**
   * Computes Luhn (modulus-10) check digit using weights 2,1,2,1... from the left.
   */
  private static int luhnMod10CheckDigit(final String nineDigits) {
    int sum = 0;

    for (int i = 0; i < 9; i++) {
      final int d = nineDigits.charAt(i) - '0';
      final int weight = (i % 2 == 0) ? 2 : 1;
      final int product = d * weight;

      sum += (product / 10) + (product % 10);
    }

    return (10 - (sum % 10)) % 10;
  }


}
