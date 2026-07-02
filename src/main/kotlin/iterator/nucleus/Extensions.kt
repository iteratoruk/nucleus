package iterator.nucleus

import java.math.BigDecimal
import java.math.RoundingMode

fun BigDecimal.toSevenDecimalPlaces(): BigDecimal = setScale(7, RoundingMode.HALF_EVEN)

fun BigDecimal.toTwoDecimalPlaces(): BigDecimal = setScale(2, RoundingMode.HALF_EVEN)
