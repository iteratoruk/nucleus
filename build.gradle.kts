import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("org.springframework.boot") version "3.5.12"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.diffplug.spotless") version "8.4.0"
	id("com.adarshr.test-logger") version "4.0.0"
	id("org.owasp.dependencycheck") version "12.2.0"
	id("com.gorylenko.gradle-git-properties") version "2.5.7"
	id("com.github.ben-manes.versions") version "0.53.0"
	id("org.sonarqube") version "7.2.3.7755"
	id("io.gitlab.arturbosch.detekt") version "1.23.8"
	id("org.jreleaser") version "1.23.0"
	kotlin("jvm") version "2.0.21"
	kotlin("plugin.allopen") version "2.0.21"
	kotlin("plugin.jpa") version "2.0.21"
	kotlin("plugin.noarg") version "2.0.21"
	kotlin("plugin.spring") version "2.0.21"
	jacoco
}

group = "iterator"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
	sourceCompatibility = JavaVersion.VERSION_21
	targetCompatibility = JavaVersion.VERSION_21
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
}

val springCloudVersion = "2025.0.0"

dependencies {
	/* Spring Boot starters */
	implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-quartz")
  implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")

	/* Compile-time dependencies */
	implementation("com.fasterxml.jackson.core:jackson-annotations")
	implementation("com.fasterxml.jackson.core:jackson-core")
	implementation("com.fasterxml.jackson.core:jackson-databind")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
  implementation("com.github.java-json-tools:json-patch:1.13")
	implementation("commons-io:commons-io:2.21.0")
  implementation("io.hypersistence:hypersistence-utils-hibernate-63:3.15.2")
	implementation("org.apache.commons:commons-lang3")
  implementation("org.flywaydb:flyway-core")
  implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
  implementation("org.redisson:redisson:4.3.0")
  implementation("org.redisson:redisson-hibernate-6:4.3.0")
  implementation("org.springframework:spring-aspects")
  implementation("org.springframework.kafka:spring-kafka")
  implementation("org.springframework.retry:spring-retry")

	/* Runtime dependencies */
	runtimeOnly("net.logstash.logback:logstash-logback-encoder:9.0")
  runtimeOnly("org.postgresql:postgresql")

	/* Development dependencies */
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

	/* Testing dependencies */
  testImplementation("com.redis:testcontainers-redis:2.2.4")
	testImplementation("com.thedeanda:lorem:2.2")
	testImplementation("org.awaitility:awaitility:4.3.0")
	testImplementation("org.awaitility:awaitility-kotlin:4.3.0")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.boot:spring-boot-testcontainers")
  testImplementation("org.springframework.kafka:spring-kafka-test")
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:kafka")
  testImplementation("org.testcontainers:postgresql")

  /* Test runtime dependencies */
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.cloud:spring-cloud-dependencies:$springCloudVersion")
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
		jvmTarget.set(JvmTarget.JVM_21)
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

noArg {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

jacoco { toolVersion = "0.8.11" }

spotless {
	kotlin {
		ktfmt("0.53")
		ktlint("1.5.0")
	}
	java { googleJavaFormat() }
	sql { target("src/main/resources/**/*.sql") }
}

testlogger {
	showPassed = false
  showExceptions = true
  showFullStackTraces = true
}

dependencyCheck {
	nvd.apiKey = System.getenv("NVD_API_KEY")
}

tasks {
	test {
		useJUnitPlatform()
		finalizedBy(jacocoTestReport)
		reports.junitXml.required.set(true)
		reports.html.required.set(true)
	}

	jacocoTestReport {
		dependsOn(test)
		reports {
			xml.required.set(true)
			html.required.set(true)
			csv.required.set(true)
		}
	}

	withType<Detekt> {
		reports.xml.required.set(true)
		reports.html.required.set(true)
	}

	jar {
		enabled = false
	}

	bootJar {
		archiveFileName.set("${archiveBaseName.get()}.${archiveExtension.get()}")
		duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	}

	bootBuildImage { imageName.set("iterator/nucleus") }

	gitProperties {
		keys = listOf(
			"git.branch",
			"git.commit.id",
			"git.commit.id.abbrev",
			"git.commit.id.describe",
			"git.commit.time",
			"git.build.version",
			"git.commit.message.short"
		)
	}

	dependencyUpdates.configure {
		val immaturityLevels = listOf("rc", "cr", "m", "beta", "alpha", "preview")
		val immaturityRegexes = immaturityLevels.map { ".*[.\\-]$it[.\\-\\d]*".toRegex(RegexOption.IGNORE_CASE) }
		fun immaturityLevel(version: String): Int = immaturityRegexes.indexOfLast { version.matches(it) }
		rejectVersionIf { immaturityLevel(candidate.version) > immaturityLevel(currentVersion) }
	}
}


