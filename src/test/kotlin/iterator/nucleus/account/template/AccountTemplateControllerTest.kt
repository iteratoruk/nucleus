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
import org.springframework.data.domain.Sort
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
      val template1 = templateFor(clientA, "alpha")
      val template2 = templateFor(clientB, "beta")
      val template3 = templateFor(clientA, "gamma")
      persistTemplates(template1, template2, template3)

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

    @Test
    fun `should return second page when list templates`() {
      // given
      val client = randomAlphanumeric(16)
      val templates =
        listOf(
          templateFor(client, "alpha"),
          templateFor(client, "beta"),
          templateFor(client, "charlie"),
        )
      persistTemplates(*templates.toTypedArray())

      val pageRequest = PageRequest.of(1, 2, Sort.by("displayName").ascending())

      // when ... then
      mvc
        .perform(
          get(baseUri)
            .header(NucleusHeaders.CLIENT_ID, client)
            .param("page", "1")
            .param("size", "2")
            .param("sort", "displayName,asc"),
        )
        .andExpect(status().isOk)
        .andExpect(
          restResource(PageResult::class.java)
            .containsOnly(
              pageOf(
                elements = listOf(templates[2].currentRepresentation),
                totalElements = 3,
                pageable = pageRequest,
                last = true,
                pageNumber = 1,
                totalPages = 2,
              ),
            ),
        )
    }

    @Test
    fun `should sort account templates in descending order by display name`() {
      // given
      val client = randomAlphanumeric(16)
      val templates =
        listOf(
          templateFor(client, "alpha"),
          templateFor(client, "beta"),
          templateFor(client, "charlie"),
        )
      persistTemplates(*templates.toTypedArray())

      val pageRequest =
        PageRequest.of(
          AccountTemplateRepository.DEFAULT_PAGE_NUMBER,
          AccountTemplateRepository.DEFAULT_PAGE_SIZE,
          Sort.by("displayName").descending(),
        )

      // when ... then
      mvc
        .perform(
          get(baseUri)
            .header(NucleusHeaders.CLIENT_ID, client)
            .param("sort", "displayName,desc"),
        )
        .andExpect(status().isOk)
        .andExpect(
          restResource(PageResult::class.java)
            .containsOnly(
              pageOf(
                elements =
                  listOf(
                    templates[2].currentRepresentation,
                    templates[1].currentRepresentation,
                    templates[0].currentRepresentation,
                  ),
                totalElements = 3,
                pageable = pageRequest,
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
      pageNumber: Int = pageable.pageNumber,
      totalPages: Int = 1,
    ): PageResult {
      val sort = sortResultOf(pageable.sort)
      return PageResult(
        content = elements,
        pageable = pageableResultOf(pageable, sort),
        last = last,
        totalPages = totalPages,
        totalElements = totalElements,
        first = pageable.isPaged && pageable.pageNumber == 0,
        size = pageable.pageSize,
        number = pageNumber,
        sort = sort,
        numberOfElements = elements.size,
        empty = elements.isEmpty(),
      )
    }

    private fun pageableResultOf(pageable: Pageable, sort: SortResult): PageableResult =
      PageableResult(
        pageNumber = pageable.pageNumber,
        pageSize = pageable.pageSize,
        sort = sort,
        offset = pageable.offset,
        paged = pageable.isPaged,
        unpaged = pageable.isUnpaged,
      )

    private fun sortResultOf(sort: Sort): SortResult =
      SortResult(sorted = sort.isSorted, unsorted = sort.isUnsorted, empty = sort.isEmpty)

    private fun templateFor(clientId: String, displayName: String): AccountTemplate {
      val template = aValidAccountTemplate().apply { createdBy = clientId }
      template.displayName = displayName
      template.currentRepresentation =
        template.currentRepresentation.copy(displayName = displayName)
      return template
    }

    private fun persistTemplates(vararg templates: AccountTemplate) {
      persistAndFlush(templates.toList())
    }
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
