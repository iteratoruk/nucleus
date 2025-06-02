package iterator.nucleus

import iterator.nucleus.TestingFu.randomAlphanumeric
import iterator.nucleus.TestingFu.randomLong
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityNotFoundException
import jakarta.transaction.Transactional
import org.assertj.core.api.BDDAssertions.then
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager
import org.springframework.context.support.GenericApplicationContext
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.annotation.Rollback
import org.springframework.test.web.servlet.MockMvc
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Instant
import java.util.Optional

@Rollback
@Transactional
@AutoConfigureTestEntityManager
abstract class AbstractJpaRepositoryTest<T : AbstractJpaEntity, R : AbstractJpaRepository<T>>(
  val repo: R,
  override val em: EntityManager,
  ctx: GenericApplicationContext,
  mvc: MockMvc,
) : AbstractApiTest(ctx, mvc),
  EntityManagerHelper<T> {
  abstract fun randomValidEntity(): T

  @Test
  fun `should return count of entities`() {
    // given
    val count = randomLong(1, 6)
    val entities = (0 until count).map { randomValidEntity() }
    persistAndFlush(entities)
    // when
    val actual = repo.count()
    // then
    then(actual).isEqualTo(count)
  }

  @Test
  fun `should delete entity with given ID when delete by ID`() {
    // given
    val entity = randomValidEntity()
    persistAndFlush(listOf(entity))
    // when
    repo.deleteById(entity.id)
    // then
    val found = find(entity.id)
    then(found).isNull()
  }

  @Test
  fun `should delete given entity when delete`() {
    // given
    val entity = randomValidEntity()
    persistAndFlush(listOf(entity))
    // when
    repo.delete(entity)
    // then
    val found = find(entity.id)
    then(found).isNull()
  }

  @Test
  fun `should delete all given entities when delete all`() {
    // given
    val a = randomValidEntity()
    val b = randomValidEntity()
    val c = randomValidEntity()
    persistAndFlush(listOf(a, b, c))
    // when
    repo.deleteAll(listOf(a, c))
    // then
    val found = findAll()
    then(found).containsExactlyElementsOf(listOf(b))
  }

  @Test
  fun `should delete all entities when delete all`() {
    // given
    val a = randomValidEntity()
    val b = randomValidEntity()
    val c = randomValidEntity()
    val entities = listOf(a, b, c)
    persistAndFlush(entities)
    // when
    repo.deleteAll()
    // then
    val found = findAll()
    then(found).isEmpty()
  }

  @Test
  fun `should return true given existing ID when exists by ID`() {
    // given
    val entity = randomValidEntity()
    persistAndFlush(listOf(entity))
    // when
    val exists = repo.existsById(entity.id)
    // then
    then(exists).isTrue
  }

  @Test
  fun `should return false given non-existent ID when exists by ID`() {
    // when .. then
    then(repo.existsById(randomLong())).isFalse
  }

  @Test
  fun `should return all entities when find all`() {
    // given
    val a = randomValidEntity()
    val b = randomValidEntity()
    val c = randomValidEntity()
    val entities = listOf(a, b, c)
    persistAndFlush(entities)
    // when
    val found = repo.findAll()
    // then
    then(found).containsExactlyInAnyOrderElementsOf(entities)
  }

  @Test
  fun `should return all entities in given order when find all with sort`() {
    // given
    val a = randomValidEntity()
    val b = randomValidEntity()
    val c = randomValidEntity()
    val entities = listOf(c, b, a)
    persistAndFlush(entities)
    // when
    val found = repo.findAll(Sort.by("id"))
    // then
    val expected = entities.sortedBy { it.id }
    then(found).containsExactlyElementsOf(expected)
  }

  @Test
  fun `should return page of entities when find all with pageable`() {
    // given
    val a = randomValidEntity()
    val b = randomValidEntity()
    val c = randomValidEntity()
    val d = randomValidEntity()
    val entities = listOf(d, c, b, a)
    persistAndFlush(entities)
    // when
    val found = repo.findAll(PageRequest.of(1, 2, Sort.by("id")))
    // then
    val expected = entities.sortedBy { it.id }.takeLast(2) // 0-based page index: we have 2nd page
    then(found.content).containsExactlyElementsOf(expected)
  }

  @Test
  fun `should return entities with given IDs when find all by IDs`() {
    // given
    val a = randomValidEntity()
    val b = randomValidEntity()
    val c = randomValidEntity()
    val entities = listOf(a, b, c)
    persistAndFlush(entities)
    // when
    val found = repo.findAllById(listOf(a.id, c.id))
    // then
    then(found).containsExactlyInAnyOrderElementsOf(listOf(a, c))
  }

  @Test
  fun `should return entity with given ID when find by ID`() {
    // given
    val entity = randomValidEntity()
    persistAndFlush(listOf(entity))
    // when
    val found = repo.findById(entity.id).get()
    // then
    then(found).isEqualTo(entity)
  }

  @Test
  fun `should return none given non-existent ID when find by ID`() {
    // when ... then
    then(repo.findById(randomLong())).isEqualTo(Optional.empty<T>())
  }

  @Test
  fun `should return entity with given ID when get reference by ID`() {
    // given
    val entity = randomValidEntity()
    persistAndFlush(listOf(entity))
    // when
    val found = repo.getReferenceById(entity.id)
    // then
    then(found).isEqualTo(entity)
  }

  @Test
  fun `should throw given non-existent ID when get one`() {
    assertThrows<EntityNotFoundException> {
      val found = repo.getReferenceById(randomLong(1, 99999))
      // fake assertion to trigger expected throw on Hibernate proxy
      then(found.toString()).isNull()
    }
  }

  @Test
  fun `should save valid entity`() {
    // given
    val entity = randomValidEntity()
    // when
    val saved = repo.save(entity)
    // then
    flush()
    val found = find(saved.id)
    then(found).isEqualTo(entity)
  }

  @Test
  fun `should save given valid entities when save all`() {
    // given
    val a = randomValidEntity()
    val b = randomValidEntity()
    val c = randomValidEntity()
    val entities = listOf(a, b, c)
    // when
    repo.saveAll(entities)
    // then
    flush()
    val found = findAll()
    then(found).containsExactlyInAnyOrderElementsOf(entities)
  }

  @Test
  fun `should set created audit fields on save`() {
    // given
    val before = Instant.now()
    val entity = randomValidEntity()
    val creator = randomAlphanumeric(8)

    // when
    var saved: T? = null
    withMockAuditor(creator) {
      saved = repo.save(entity)
      flush()
    }
    val after = Instant.now()

    // then
    val found = find(saved!!.id)!!
    then(found.createdBy).isEqualTo(creator)
    then(found.createdDate).isNotNull().isBetween(before, after)
  }

  protected fun withMockAuditor(
    clientId: String,
    block: () -> Unit,
  ) {
    val request = MockHttpServletRequest()
    request.setAttribute("X-Client-ID", clientId)
    val attributes = ServletRequestAttributes(request)
    RequestContextHolder.setRequestAttributes(attributes)
    try {
      block()
    } finally {
      RequestContextHolder.resetRequestAttributes()
    }
  }
}

abstract class AbstractMutableJpaRepositoryTest<
  T : AbstractMutableJpaEntity,
  R : AbstractJpaRepository<T>,
>(
  repo: R,
  em: EntityManager,
  ctx: GenericApplicationContext,
  mvc: MockMvc,
) : AbstractJpaRepositoryTest<T, R>(repo, em, ctx, mvc) {
  abstract fun mutateEntity(entity: T)

  @Test
  fun `should update version when save`() {
    // given
    val entity = randomValidEntity()
    persistAndFlush(listOf(entity))
    val version = entity.version
    mutateEntity(entity)
    // when
    val saved = repo.save(entity)
    // then
    flush()
    val found = find(saved.id)!!
    then(found.version).isEqualTo(version + 1)
  }

  @Test
  fun `should set modified audit fields on update`() {
    // given
    val entity = randomValidEntity()
    val creator = randomAlphanumeric(8)
    withMockAuditor(creator) {
      repo.save(entity)
      flush()
    }

    // when
    val updater = randomAlphanumeric(8)
    var updated: T? = null
    withMockAuditor(updater) {
      mutateEntity(entity)
      updated = repo.save(entity)
      flush()
    }

    // then
    val found = find(updated!!.id)!!
    then(found.createdBy).isEqualTo(creator)
    then(found.lastModifiedBy).isEqualTo(updater)
    then(found.lastModifiedDate).isAfter(found.createdDate)
  }
}
