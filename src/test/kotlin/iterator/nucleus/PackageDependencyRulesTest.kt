package iterator.nucleus

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

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
}
