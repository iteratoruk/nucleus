package iterator.nucleus

import org.hibernate.boot.registry.StandardServiceRegistry
import org.hibernate.cache.CacheException
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.codec.JsonJacksonCodec
import org.redisson.config.Config
import org.redisson.hibernate.RedissonRegionFactory

/**
 * A [RedissonRegionFactory] that serialises the Hibernate second-level cache with the application's
 * Jackson JSON configuration ([Serialization.mapper]) instead of Redisson's default, which is JDK
 * (Java) serialisation — brittle across class-shape changes and a portability and security
 * liability.
 *
 * Redisson's [JsonJacksonCodec] *mutates* the mapper it is given: it activates polymorphic default
 * typing (which Hibernate's cache structures need to round-trip) and adjusts visibility. It is
 * therefore handed a `copy()` of the shared mapper so those cache-only concerns never leak into the
 * one application-wide mapper used for HTTP, Kafka, and idempotency serialisation.
 */
class JsonRedissonRegionFactory : RedissonRegionFactory() {
  override fun createRedissonClient(
    registry: StandardServiceRegistry,
    properties: MutableMap<*, *>,
  ): RedissonClient {
    val path =
      properties[RedissonRegionFactory.REDISSON_CONFIG_PATH]?.toString() ?: DEFAULT_CONFIG_PATH
    return Redisson.create(buildConfig(path))
  }

  companion object {
    const val DEFAULT_CONFIG_PATH = "redisson.yml"

    /**
     * Loads the Redisson configuration from the classpath (same source and `${VAR:-default}`
     * substitution as the base factory) and sets the JSON codec. Extracted so the codec wiring is
     * testable without opening a connection to Redis.
     */
    fun buildConfig(path: String): Config {
      val config =
        JsonRedissonRegionFactory::class.java.classLoader.getResourceAsStream(path)?.use {
          Config.fromYAML(it)
        } ?: throw CacheException("Unable to locate Redisson configuration '$path'")
      config.codec = JsonJacksonCodec(Serialization.mapper.copy())
      return config
    }
  }
}
