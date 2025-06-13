package iterator.nucleus.account.feature

import iterator.nucleus.kafka.KafkaConstants

object FeatureConstants {
  const val PRIVATE_FEATURE_TOPIC_PREFIX = "${KafkaConstants.PRIVATE_TOPIC_PREFIX}account.feature."

  // define feature name constants here, so that we can easily see they are all unique
  const val INTEREST_FEATURE_NAME = "INTEREST"
}
