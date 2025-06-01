package iterator.nucleus

import com.redis.testcontainers.RedisContainer
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

interface TestContainers {
  companion object {
    val postgresContainer: PostgreSQLContainer<*> =
      PostgreSQLContainer(DockerImageName.parse("postgres:17.5"))

    val redisContainer: RedisContainer = RedisContainer(DockerImageName.parse("redis:8.0"))

    init {
      postgresContainer.start()
      redisContainer.start()
    }

    @JvmStatic
    @DynamicPropertySource
    fun dynamicProperties(registry: DynamicPropertyRegistry) {
      registry.add("spring.datasource.url", postgresContainer::getJdbcUrl)
      registry.add("spring.datasource.username", postgresContainer::getUsername)
      registry.add("spring.datasource.password", postgresContainer::getPassword)
      registry.add("spring.flyway.url", postgresContainer::getJdbcUrl)
      registry.add("spring.flyway.user", postgresContainer::getUsername)
      registry.add("spring.flyway.password", postgresContainer::getPassword)
      registry.add("spring.data.redis.host", redisContainer::getHost)
      registry.add("spring.data.redis.port") { redisContainer.firstMappedPort.toString() }
      System.setProperty("SPRING_DATA_REDIS_HOST", redisContainer.host)
      System.setProperty("SPRING_DATA_REDIS_PORT", redisContainer.firstMappedPort.toString())
    }
  }
}
