package iterator.nucleus.accountfeatures

import com.fasterxml.jackson.module.kotlin.convertValue
import iterator.nucleus.AbstractJpaEntity
import iterator.nucleus.AbstractJpaRepository
import iterator.nucleus.NucleusHeaders
import iterator.nucleus.NucleusValidationException
import iterator.nucleus.NucleusViolation
import iterator.nucleus.Serialization
import iterator.nucleus.Uris
import iterator.nucleus.idempotency.IdempotencyService
import iterator.nucleus.parameters.ABSENCE_MARKER
import iterator.nucleus.parameters.ClassificationCode
import iterator.nucleus.parameters.LedgerSide
import iterator.nucleus.parameters.ParameterNodeService
import iterator.nucleus.sevenDecimalPlaceViolation
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

enum class ProcessingBoundary {
  BUSINESS_DAY_CLOSE,
  WEEK_CLOSE,
  MONTH_CLOSE,
  QUARTER_CLOSE,
  YEAR_CLOSE,
  TAX_YEAR_CLOSE,
}

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class BoundaryGoverned(
  val boundary: ProcessingBoundary,
)

@Entity
class ProcessingBoundaryClosure(
  @Enumerated(EnumType.STRING) val boundary: ProcessingBoundary,
  val closureTimestamp: Instant,
) : AbstractJpaEntity()

interface ProcessingBoundaryClosureRepository : AbstractJpaRepository<ProcessingBoundaryClosure> {
  fun findTopByBoundaryOrderByClosureTimestampDesc(boundary: ProcessingBoundary): ProcessingBoundaryClosure?
}

interface AccountFeature

data class LiabilityInterestFeature(
  val enabled: Boolean? = null,
  @BoundaryGoverned(ProcessingBoundary.BUSINESS_DAY_CLOSE) val interestRate: BigDecimal? = null,
) : AccountFeature

data class AssetInterestFeature(
  val enabled: Boolean? = null,
  @BoundaryGoverned(ProcessingBoundary.BUSINESS_DAY_CLOSE) val interestRate: BigDecimal? = null,
) : AccountFeature

data class FeatureConfiguration(
  val liabilityInterest: LiabilityInterestFeature? = null,
  val assetInterest: AssetInterestFeature? = null,
) {
  fun presentFeatures(): List<AccountFeature> = listOfNotNull(liabilityInterest, assetInterest)
}

private fun featureNameFor(klass: KClass<out AccountFeature>): String =
  klass.simpleName!!.removeSuffix("Feature").replaceFirstChar { it.lowercase() }

@Component
class FeatureCatalogueConverter {
  private val objectMapper = Serialization.mapper

  fun toFeatureConfiguration(parameterValues: Map<String, String>): FeatureConfiguration {
    val byFeature =
      parameterValues.entries
        .groupBy { it.key.substringBefore(".") }
        .mapValues { (_, entries) ->
          entries.associate { it.key.substringAfter(".") to it.value }
        }
    return FeatureConfiguration(
      liabilityInterest =
        byFeature["liabilityInterest"]?.let {
          objectMapper.convertValue(it, LiabilityInterestFeature::class.java)
        },
      assetInterest =
        byFeature["assetInterest"]?.let {
          objectMapper.convertValue(it, AssetInterestFeature::class.java)
        },
    )
  }

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
  val explicitAbsences: Map<String, List<String>> = emptyMap(),
)

data class AccountFeaturesResponse(
  val features: FeatureConfiguration,
)

@RestController
@RequestMapping("${Uris.API_V1}/account-features")
class AccountFeaturesController(
  val service: AccountFeaturesService,
) {
  @GetMapping("/{classificationCode}")
  fun get(
    @PathVariable classificationCode: String,
    @RequestParam(required = false) asAt: Instant?,
  ): AccountFeaturesResponse = service.get(classificationCode, asAt ?: Instant.now())

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

private fun ledgerSidePrefixViolation(code: String): NucleusViolation? {
  val prefix = code.substringBefore("_")
  return if (LedgerSide.entries.none { it.name == prefix }) {
    NucleusViolation(
      code,
      "Invalid classification code '$code': '$prefix' is not a recognised ledger-side prefix; " +
        "the first segment must be one of ${LedgerSide.entries.joinToString()}",
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
  val processingBoundaryClosureRepository: ProcessingBoundaryClosureRepository,
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

    val code = validateAndParseClassificationCode(classificationCode)
    val violations =
      ledgerSideApplicabilityViolations(code.ledgerSide, request.features) +
        propertyConstraintViolations(request.features) +
        opennessViolations(request.features, request.effectiveDatetime)
    if (violations.isNotEmpty()) throw NucleusValidationException(violations)
    val absenceValues =
      request.explicitAbsences
        .flatMap { (featureName, propertyNames) ->
          propertyNames.map { propertyName -> "$featureName.$propertyName" to ABSENCE_MARKER }
        }.toMap()
    parameterNodeService.write(
      code,
      request.effectiveDatetime,
      featureCatalogueConverter.toParameterValues(request.features) + absenceValues,
    )
    val response =
      AccountFeaturesResponse(
        features =
          featureCatalogueConverter.toFeatureConfiguration(
            parameterNodeService.resolve(code, request.effectiveDatetime),
          ),
      )
    idempotencyService.record(
      operationId = PUT_ACCOUNT_FEATURES,
      idempotencyKey = idempotencyKey,
      uri = "${Uris.API_V1}/account-features/$classificationCode",
      response = response,
    )
    return response
  }

  private fun opennessViolations(
    features: FeatureConfiguration,
    effectiveDatetime: Instant,
  ): List<NucleusViolation> =
    features.presentFeatures().flatMap { feature ->
      val featureName = featureNameFor(feature::class)
      feature::class.memberProperties.mapNotNull { property ->
        val annotation = property.findAnnotation<BoundaryGoverned>() ?: return@mapNotNull null
        if (property.getter.call(feature) == null) return@mapNotNull null
        val closure =
          processingBoundaryClosureRepository.findTopByBoundaryOrderByClosureTimestampDesc(
            annotation.boundary,
          ) ?: return@mapNotNull null
        val businessDate = effectiveDatetime.atZone(ZoneOffset.UTC).toLocalDate()
        val closureDate = closure.closureTimestamp.atZone(ZoneOffset.UTC).toLocalDate()
        if (businessDate <= closureDate) {
          NucleusViolation(
            "$featureName.${property.name}",
            "business date $businessDate is closed under ${annotation.boundary} boundary",
          )
        } else {
          null
        }
      }
    }

  fun get(
    classificationCode: String,
    asAt: Instant,
  ): AccountFeaturesResponse {
    val code = validateAndParseClassificationCode(classificationCode)
    return AccountFeaturesResponse(
      features =
        featureCatalogueConverter.toFeatureConfiguration(
          parameterNodeService.resolve(code, asAt),
        ),
    )
  }

  private fun validateAndParseClassificationCode(code: String): ClassificationCode {
    val violation = classificationCodeViolation(code) ?: ledgerSidePrefixViolation(code)
    if (violation != null) throw NucleusValidationException(listOf(violation))
    return ClassificationCode(code)
  }
}
