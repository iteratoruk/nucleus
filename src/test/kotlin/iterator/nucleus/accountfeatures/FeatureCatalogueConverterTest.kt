package iterator.nucleus.accountfeatures

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FeatureCatalogueConverterTest {
  private val converter = FeatureCatalogueConverter()

  @Test
  fun `non-null feature properties are converted to parameter key-value pairs`() {
    val features =
      FeatureConfiguration(
        liabilityInterest =
          LiabilityInterestFeature(enabled = true, interestRate = "0.0350000"),
      )

    assertThat(converter.toParameterValues(features))
      .containsExactlyInAnyOrderEntriesOf(
        mapOf(
          "liabilityInterest.enabled" to "true",
          "liabilityInterest.interestRate" to "0.0350000",
        ),
      )
  }

  @Test
  fun `null feature properties produce no entries`() {
    val features =
      FeatureConfiguration(
        liabilityInterest = LiabilityInterestFeature(enabled = true, interestRate = null),
      )

    assertThat(converter.toParameterValues(features))
      .containsExactlyEntriesOf(mapOf("liabilityInterest.enabled" to "true"))
  }

  @Test
  fun `absent features produce no entries`() {
    assertThat(converter.toParameterValues(FeatureConfiguration())).isEmpty()
  }

  @Test
  fun `multiple features are converted independently`() {
    val features =
      FeatureConfiguration(
        liabilityInterest = LiabilityInterestFeature(enabled = true),
        assetInterest = AssetInterestFeature(interestRate = "0.0750000"),
      )

    assertThat(converter.toParameterValues(features))
      .containsExactlyInAnyOrderEntriesOf(
        mapOf(
          "liabilityInterest.enabled" to "true",
          "assetInterest.interestRate" to "0.0750000",
        ),
      )
  }
}
