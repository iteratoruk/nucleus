package iterator.nucleus.accountfeatures

import com.fasterxml.jackson.module.kotlin.convertValue
import iterator.nucleus.NucleusHeaders
import iterator.nucleus.NucleusValidationException
import iterator.nucleus.NucleusViolation
import iterator.nucleus.Serialization
import iterator.nucleus.Uris
import iterator.nucleus.idempotency.IdempotencyService
import iterator.nucleus.parameters.ClassificationCode
import iterator.nucleus.parameters.LedgerSide
import iterator.nucleus.parameters.ParameterNodeService
import iterator.nucleus.sevenDecimalPlaceViolation
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

interface AccountFeature

data class LiabilityInterestFeature(
  val enabled: Boolean? = null,
  val interestRate: BigDecimal? = null,
) : AccountFeature

data class AssetInterestFeature(
  val enabled: Boolean? = null,
  val interestRate: BigDecimal? = null,
) : AccountFeature

data class FeatureConfiguration(
  val liabilityInterest: LiabilityInterestFeature? = null,
  val assetInterest: AssetInterestFeature? = null,
) {
  fun presentFeatures(): List<AccountFeature> = listOfNotNull(liabilityInterest, assetInterest)
}

private val featureNameCache = ConcurrentHashMap<KClass<*>, String>()

private fun featureNameFor(klass: KClass<out AccountFeature>): String =
  featureNameCache.getOrPut(klass) {
    klass.simpleName!!.removeSuffix("Feature").replaceFirstChar { it.lowercase() }
  }

@Component
class FeatureCatalogueConverter {
  private val objectMapper = Serialization.mapper

  fun toParameterValues(features: FeatureConfiguration): Map<String, String> =
    features
      .presentFeatures()
      .flatMap { feature ->
        val featureName = featureNameFor(feature::class)
        objectMapper.convertValue<Map<String, Any>>(feature).map { (propertyName, value) ->
          "$featureName.$propertyName" to value.toString()
        }
      }.toMap()
}

private const val PUT_ACCOUNT_FEATURES = "PUT_ACCOUNT_FEATURES"

data class PutAccountFeaturesRequest(
  val effectiveDatetime: Instant,
  val features: FeatureConfiguration,
)

data class AccountFeaturesResponse(
  val features: FeatureConfiguration,
)

@RestController
@RequestMapping("${Uris.API_V1}/account-features")
class AccountFeaturesController(
  val service: AccountFeaturesService,
) {
  @PutMapping("/{classificationCode}")
  fun put(
    @PathVariable classificationCode: String,
    @RequestHeader(NucleusHeaders.IDEMPOTENCY_KEY) idempotencyKey: String,
    @RequestBody request: PutAccountFeaturesRequest,
  ): AccountFeaturesResponse = service.put(classificationCode, idempotencyKey, request)
}

private val classificationCodeSegmentPattern = Regex("[A-Z0-9]{4}")

private fun classificationCodeViolation(code: String): NucleusViolation? {
  val segments = code.split("_")
  val valid = segments.isNotEmpty() && segments.all { classificationCodeSegmentPattern.matches(it) }
  return if (!valid) {
    NucleusViolation(
      code,
      "Invalid classification code '$code': each segment must be exactly 4 uppercase alphanumeric characters separated by underscores",
    )
  } else {
    null
  }
}

private val featureLedgerSideApplicability: Map<String, Set<LedgerSide>> =
  mapOf(
    "assetInterest" to setOf(LedgerSide.ASST),
    "liabilityInterest" to setOf(LedgerSide.LIAB),
  )

private fun propertyConstraintViolations(features: FeatureConfiguration): List<NucleusViolation> =
  listOfNotNull(
    features.liabilityInterest?.let {
      sevenDecimalPlaceViolation("liabilityInterest", "interestRate", it.interestRate)
    },
    features.assetInterest?.let {
      sevenDecimalPlaceViolation("assetInterest", "interestRate", it.interestRate)
    },
  )

private fun ledgerSideApplicabilityViolations(
  ledgerSide: LedgerSide,
  features: FeatureConfiguration,
): List<NucleusViolation> =
  features.presentFeatures().mapNotNull { feature ->
    val name = featureNameFor(feature::class)
    val applicable = featureLedgerSideApplicability[name]
    if (applicable != null && ledgerSide !in applicable) {
      NucleusViolation(name, "Feature '$name' is not applicable to ledger side $ledgerSide")
    } else {
      null
    }
  }

@Service
@Transactional
class AccountFeaturesService(
  val parameterNodeService: ParameterNodeService,
  val featureCatalogueConverter: FeatureCatalogueConverter,
  val idempotencyService: IdempotencyService,
) {
  fun put(
    classificationCode: String,
    idempotencyKey: String,
    request: PutAccountFeaturesRequest,
  ): AccountFeaturesResponse {
    idempotencyService
      .findExistingResponse(PUT_ACCOUNT_FEATURES, idempotencyKey, AccountFeaturesResponse::class)
      ?.let {
        return it
      }

    val codeViolation = classificationCodeViolation(classificationCode)
    if (codeViolation != null) throw NucleusValidationException(listOf(codeViolation))
    val code = ClassificationCode(classificationCode)
    val violations =
      ledgerSideApplicabilityViolations(code.ledgerSide, request.features) +
        propertyConstraintViolations(request.features)
    if (violations.isNotEmpty()) throw NucleusValidationException(violations)
    parameterNodeService.write(
      code,
      request.effectiveDatetime,
      featureCatalogueConverter.toParameterValues(request.features),
    )
    val response = AccountFeaturesResponse(features = request.features)
    idempotencyService.record(
      operationId = PUT_ACCOUNT_FEATURES,
      idempotencyKey = idempotencyKey,
      uri = "${Uris.API_V1}/account-features/$classificationCode",
      response = response,
    )
    return response
  }
}
