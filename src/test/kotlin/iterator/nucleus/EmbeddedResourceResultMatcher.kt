package iterator.nucleus

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.BDDAssertions.then
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultMatcher

class EmbeddedResourceResultMatchers<T>
  private constructor(
    private val clazz: Class<T>,
  ) {
    companion object {
      fun <T> restResource(clazz: Class<T>): EmbeddedResourceResultMatchers<T> = EmbeddedResourceResultMatchers(clazz)
    }

    private var mapper: ObjectMapper = Serialization.mapper

    fun usingMapper(mapper: ObjectMapper): EmbeddedResourceResultMatchers<T> {
      this.mapper = mapper
      return this
    }

    fun containsResourcesExactlyInOrder(
      expected: List<T>,
      vararg ignoring: String,
    ): EmbeddedResourcesResultMatcher<T> = EmbeddedResourcesResultMatcher(clazz, expected, true, mapper, *ignoring)

    fun containsResources(
      expected: List<T>,
      vararg ignoring: String,
    ): EmbeddedResourcesResultMatcher<T> = EmbeddedResourcesResultMatcher(clazz, expected, false, mapper, *ignoring)

    fun containsOnly(
      expected: T,
      vararg ignoring: String,
    ): EmbeddedResourceResultMatcher<T> = EmbeddedResourceResultMatcher(clazz, expected, mapper, *ignoring)
  }

class EmbeddedResourcesResultMatcher<T>(
  private val clazz: Class<T>,
  private val expected: List<T>,
  private val inOrder: Boolean,
  private val mapper: ObjectMapper,
  private vararg val ignoring: String,
) : ResultMatcher {
  override fun match(result: MvcResult) {
    val resources =
      mapper.readValue<List<T>>(
        result.response.contentAsString,
        mapper.typeFactory.constructCollectionType(List::class.java, clazz),
      )
    if (inOrder) {
      then(resources)
        .usingRecursiveFieldByFieldElementComparatorIgnoringFields(*ignoring)
        .containsExactlyElementsOf(expected)
    } else {
      then(resources)
        .usingRecursiveFieldByFieldElementComparatorIgnoringFields(*ignoring)
        .containsExactlyInAnyOrderElementsOf(expected)
    }
  }
}

class EmbeddedResourceResultMatcher<T>(
  private val clazz: Class<T>,
  private val expected: T,
  private val mapper: ObjectMapper,
  private vararg val ignoring: String,
) : ResultMatcher {
  override fun match(result: MvcResult) {
    val resource = mapper.readValue(result.response.contentAsString, clazz)
    then(resource).usingRecursiveComparison().ignoringFields(*ignoring).isEqualTo(expected)
  }
}
