package iterator.nucleus

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.ControllerAdvice

/**
 * Enforces the package-dependency rules that keep the cross-cutting concerns cohesive. Feature
 * packages may depend on the top-level parent package (shared infrastructure), but must not
 * accumulate dependencies on each other in ways that create cycles.
 */
class PackageDependencyRulesTest {
  private val productionClasses =
    ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("iterator.nucleus")

  @Test
  fun `audit must not depend on peer feature packages`() {
    // The audit package exports its machinery (AuditService, AbstractAuditEvent,
    // NucleusAuditEventType) for others to depend on; it must depend on no feature package itself,
    // so it can never be one half of a cycle. Each feature owns its own audit event types.
    noClasses()
      .that()
      .resideInAPackage("iterator.nucleus.audit..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "iterator.nucleus.schedule..",
        "iterator.nucleus.kafka..",
        "iterator.nucleus.idempotency..",
      ).check(productionClasses)
  }

  @Test
  fun `the top-level package must not depend on any feature package`() {
    // The top-level package holds the shared infrastructure — the error catalogue
    // (ErrorHandler, NucleusErrorCode, NucleusError, the Nucleus exceptions), persistence base
    // classes, serialization, cache. It is a leaf that features depend on, never the reverse, so
    // it cannot be dragged into a cycle and the error-mapping types stay isolated.
    noClasses()
      .that()
      .resideInAPackage("iterator.nucleus")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "iterator.nucleus.audit..",
        "iterator.nucleus.kafka..",
        "iterator.nucleus.idempotency..",
        "iterator.nucleus.schedule..",
      ).check(productionClasses)
  }

  @Test
  fun `controller advice must live only in the top-level package`() {
    // The single @ControllerAdvice is the one error-mapping seam; a sub-package must not define its
    // own advice and splinter the error catalogue.
    classes()
      .that()
      .areAnnotatedWith(ControllerAdvice::class.java)
      .should()
      .resideInAPackage("iterator.nucleus")
      .check(productionClasses)
  }

  @Test
  fun `idempotency must not depend on peer feature packages`() {
    // Idempotency stays self-contained — it references only the top-level parent package and
    // external libraries — so the declarative @Idempotent support can be applied across features
    // without dragging domain packages into it.
    noClasses()
      .that()
      .resideInAPackage("iterator.nucleus.idempotency..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "iterator.nucleus.audit..",
        "iterator.nucleus.kafka..",
        "iterator.nucleus.schedule..",
      ).check(productionClasses)
  }
}
