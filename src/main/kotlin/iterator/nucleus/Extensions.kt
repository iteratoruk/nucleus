package iterator.nucleus

import java.math.BigDecimal
import java.math.RoundingMode

fun BigDecimal.toSevenDecimalPlaces(): BigDecimal = setScale(7, RoundingMode.HALF_EVEN)

fun BigDecimal.toTwoDecimalPlaces(): BigDecimal = setScale(2, RoundingMode.HALF_EVEN)

fun sevenDecimalPlaceViolation(
  subject: String,
  property: String,
  value: BigDecimal?,
): NucleusViolation? =
  if (value != null && value.scale() > 7) {
    NucleusViolation(subject, "'$property' has ${value.scale()} decimal places; maximum is 7")
  } else {
    null
  }

fun twoDecimalPlaceViolation(
  subject: String,
  property: String,
  value: BigDecimal?,
): NucleusViolation? =
  if (value != null && value.scale() > 2) {
    NucleusViolation(subject, "'$property' has ${value.scale()} decimal places; maximum is 2")
  } else {
    null
  }
