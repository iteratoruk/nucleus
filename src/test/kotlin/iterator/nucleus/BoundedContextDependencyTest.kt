package iterator.nucleus

import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(packages = ["iterator.nucleus"])
class BoundedContextDependencyTest {
  @ArchTest
  val parametersMustNotDependOnAccountfeatures: ArchRule =
    noClasses()
      .that()
      .resideInAPackage("iterator.nucleus.parameters..")
      .should()
      .dependOnClassesThat()
      .resideInAPackage("iterator.nucleus.accountfeatures..")
      .because(
        "parameters is the foundational bounded context; " +
          "it must not depend on any other bounded context (ADR-012)",
      )
}
