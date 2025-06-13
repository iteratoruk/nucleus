package iterator.nucleus

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible

fun Instant.truncatedToPostgresAccuracy(): Instant = truncatedTo(ChronoUnit.MICROS)

inline fun <reified V> Any.getPrivateFieldValue(name: String): V? {
  val kProp = this::class.declaredMemberProperties.firstOrNull { it.name == name }
  if (kProp != null) {
    kProp.isAccessible = true
    return kProp.getter.call(this) as V?
  }
  val javaField = this::class.java.getDeclaredField(name).apply { isAccessible = true }
  return javaField.get(this) as V?
}
