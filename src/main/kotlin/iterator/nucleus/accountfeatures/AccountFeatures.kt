package iterator.nucleus.accountfeatures

import com.fasterxml.jackson.module.kotlin.convertValue
import iterator.nucleus.Serialization
import iterator.nucleus.Uris
import iterator.nucleus.parameters.ClassificationCode
import iterator.nucleus.parameters.ParameterNodeService
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

interface AccountFeature

data class LiabilityInterestFeature(
  val enabled: Boolean? = null,
  val interestRate: String? = null,
) : AccountFeature

data class AssetInterestFeature(
  val enabled: Boolean? = null,
  val interestRate: String? = null,
) : AccountFeature

data class FeatureConfiguration(
  val liabilityInterest: LiabilityInterestFeature? = null,
  val assetInterest: AssetInterestFeature? = null,
) {
  fun presentFeatures(): List<AccountFeature> = listOfNotNull(liabilityInterest, assetInterest)
}

@Component
class FeatureCatalogueConverter {
  private val objectMapper = Serialization.mapper
  private val featureNameCache = ConcurrentHashMap<KClass<*>, String>()

  fun toParameterValues(features: FeatureConfiguration): Map<String, String> =
    features
      .presentFeatures()
      .flatMap { feature ->
        val featureName = featureNameFor(feature::class)
        objectMapper.convertValue<Map<String, Any>>(feature).map { (propertyName, value) ->
          "$featureName.$propertyName" to value.toString()
        }
      }.toMap()

  private fun featureNameFor(klass: KClass<*>): String =
    featureNameCache.getOrPut(klass) {
      klass.simpleName!!.removeSuffix("Feature").replaceFirstChar { it.lowercase() }
    }
}

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
    @RequestBody request: PutAccountFeaturesRequest,
  ): AccountFeaturesResponse = service.put(classificationCode, request)
}

@Service
@Transactional
class AccountFeaturesService(
  val parameterNodeService: ParameterNodeService,
  val featureCatalogueConverter: FeatureCatalogueConverter,
) {
  fun put(
    classificationCode: String,
    request: PutAccountFeaturesRequest,
  ): AccountFeaturesResponse {
    val code = ClassificationCode(classificationCode)
    parameterNodeService.write(
      code,
      request.effectiveDatetime,
      featureCatalogueConverter.toParameterValues(request.features),
    )
    return AccountFeaturesResponse(features = request.features)
  }
}
