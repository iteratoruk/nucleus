package iterator.nucleus.account.template

import iterator.nucleus.NucleusHeaders
import iterator.nucleus.Uris
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("${Uris.API_V1}${AccountTemplateController.REL}")
class AccountTemplateController(
  val repo: AccountTemplateRepository,
) {
  companion object {
    const val REL = "/account-templates"
  }

  @GetMapping
  fun handleListAccountTemplates(
    @RequestHeader(NucleusHeaders.CLIENT_ID) clientId: String,
    pageable: Pageable,
  ): Page<AccountTemplateRepresentation> =
    repo.findByCreatedBy(clientId, pageable).map { it.currentRepresentation }
}
