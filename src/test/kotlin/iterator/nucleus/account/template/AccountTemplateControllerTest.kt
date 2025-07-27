package iterator.nucleus.account.template

import iterator.nucleus.AbstractApiTest
import iterator.nucleus.AbstractJpaEntity
import iterator.nucleus.EmbeddedResourceResultMatchers.Companion.restResource
import iterator.nucleus.EntityManagerHelper
import iterator.nucleus.NucleusError
import iterator.nucleus.NucleusErrorCode
import iterator.nucleus.NucleusHeaders
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.randomAlphanumeric
import iterator.nucleus.Uris
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager
import org.springframework.context.support.GenericApplicationContext
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.test.annotation.Rollback
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@Rollback
@Transactional
@AutoConfigureTestEntityManager
class AccountTemplateControllerTest
  @Autowired
  constructor(
    ctx: GenericApplicationContext,
    mvc: MockMvc,
    override val em: EntityManager,
  ) : AbstractApiTest(ctx, mvc),
    EntityManagerHelper<AbstractJpaEntity> {
    val baseUri = "${Uris.API_V1}${AccountTemplateController.REL}"

    val defaultPageable =
      PageRequest.of(
        AccountTemplateRepository.DEFAULT_PAGE_NUMBER,
        AccountTemplateRepository.DEFAULT_PAGE_SIZE,
      )

    @Test
    fun `should return bad request given X-Client-ID header missing when list templates`() {
      // when ... then
      mvc
        .perform(get(baseUri))
        .andExpect(status().isBadRequest)
        .andExpect(
          restResource(NucleusError::class.java)
            .containsOnly(
              NucleusError(
                code = NucleusErrorCode.MISSING_HEADER,
                message = "Missing ${NucleusHeaders.CLIENT_ID} header",
              ),
            ),
        )
    }

    @Test
    fun `should return account templates belonging to client when list templates`() {
      // given
      val clientA = randomAlphanumeric(16)
      val clientB = randomAlphanumeric(16)
      val template1 = aValidAccountTemplate().apply { createdBy = clientA }
      val template2 = aValidAccountTemplate().apply { createdBy = clientB }
      val template3 = aValidAccountTemplate().apply { createdBy = clientA }
      persistAndFlush(listOf(template1, template2, template3))

      // when ... then
      mvc
        .perform(get(baseUri).header(NucleusHeaders.CLIENT_ID, clientA))
        .andExpect(status().isOk)
        .andExpect(
          restResource(PageResult::class.java)
            .containsOnly(
              pageOf(
                elements =
                  listOf(
                    template1.currentRepresentation,
                    template3.currentRepresentation,
                  ),
                totalElements = 2,
                pageable = defaultPageable,
                last = true,
                pageNumber = 0,
                totalPages = 1,
              ),
            ),
        )
    }

    override fun entityClass(): Class<AbstractJpaEntity> = AbstractJpaEntity::class.java

    private fun pageOf(
      elements: List<AccountTemplateRepresentation>,
      totalElements: Long,
      pageable: Pageable,
      last: Boolean = false,
      pageNumber: Int = 0,
      totalPages: Int = 1,
    ): PageResult =
      PageResult(
        content = elements,
        pageable =
          PageableResult(
            pageNumber = pageable.pageNumber,
            pageSize = pageable.pageSize,
            sort =
              SortResult(
                sorted = pageable.sort.isSorted,
                unsorted = pageable.sort.isUnsorted,
                empty = pageable.sort.isEmpty,
              ),
            offset = pageable.offset,
            paged = pageable.isPaged,
            unpaged = pageable.isUnpaged,
          ),
        last = last,
        totalPages = totalPages,
        totalElements = totalElements,
        first = pageable.isPaged && pageable.pageNumber == 0,
        size = pageable.pageSize,
        number = pageNumber,
        sort =
          SortResult(
            sorted = pageable.sort.isSorted,
            unsorted = pageable.sort.isUnsorted,
            empty = pageable.sort.isEmpty,
          ),
        numberOfElements = elements.size,
        empty = elements.isEmpty(),
      )
  }

data class PageResult(
  val content: List<AccountTemplateRepresentation>,
  val pageable: PageableResult,
  val last: Boolean,
  val totalPages: Int,
  val totalElements: Long,
  val first: Boolean,
  val size: Int,
  val number: Int,
  val sort: SortResult,
  val numberOfElements: Int,
  val empty: Boolean,
)

data class PageableResult(
  val pageNumber: Int,
  val pageSize: Int,
  val sort: SortResult,
  val offset: Long,
  val paged: Boolean,
  val unpaged: Boolean,
)

data class SortResult(
  val sorted: Boolean,
  val unsorted: Boolean,
  val empty: Boolean,
)
