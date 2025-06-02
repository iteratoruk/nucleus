package iterator.nucleus

import com.thedeanda.lorem.Lorem
import com.thedeanda.lorem.LoremIpsum
import iterator.nucleus.account.Account
import iterator.nucleus.account.template.AccountTemplate
import iterator.nucleus.customer.CustomerTranche
import iterator.nucleus.ledger.LedgerEntry
import iterator.nucleus.ledger.LedgerEntryType
import org.apache.commons.lang3.RandomStringUtils
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalUnit
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

object TestingFu {
  private val lorem: Lorem = LoremIpsum.getInstance()

  val tlds =
    listOf("com", "org", "net", "edu", "gov", "co.uk", "gov.uk", "ac.uk", "org.uk", "biz", "info")

  // generic random generators

  fun randomBoolean(): Boolean = Random.nextBoolean()

  fun randomInt(): Int = Random.nextInt()

  fun randomInt(
    from: Int,
    until: Int,
  ): Int = if (from == until) until else Random.nextInt(from, until)

  fun randomPaddedInt(
    from: Int,
    until: Int,
    padLength: Int,
    padChar: Char = '0',
  ): String = Random.nextInt(from, until).toString().padStart(padLength, padChar)

  fun randomLong(): Long = Random.nextLong()

  fun randomLong(
    from: Long,
    until: Long,
  ): Long = if (from == until) until else Random.nextLong(from, until)

  fun randomPaddedLong(
    from: Long,
    until: Long,
    padLength: Int,
    padChar: Char = '0',
  ): String = Random.nextLong(from, until).toString().padStart(padLength, padChar)

  fun randomDouble(
    from: Double,
    until: Double,
  ): Double = Random.nextDouble(from, until)

  fun randomBigDecimal(
    from: Double,
    until: Double,
  ): BigDecimal =
    if (from == until) {
      from.toBigDecimal()
    } else {
      randomDouble(from, until).toBigDecimal().setScale(2, RoundingMode.HALF_EVEN)
    }

  fun randomWords(count: Int) = lorem.getWords(count)

  fun randomWords(
    min: Int,
    max: Int,
  ) = lorem.getWords(min, max)

  fun randomLocalDate(): LocalDate {
    val month = Month.of(randomInt(1, 13))
    // deliberately NOT using last day of any month
    // in order to avoid February in leap year kerfuffle
    val day = randomInt(1, month.maxLength())
    return LocalDate.of(randomInt(1, LocalDate.now().year + 1), month, day)
  }

  fun randomLocalDateTimeInThePast(
    startRange: Long = randomLong(1, 1000),
    endRange: Long = randomLong(1001, 2000),
    unit: TemporalUnit = ChronoUnit.SECONDS,
  ): LocalDateTime = LocalDateTime.now().minus(randomLong(startRange, endRange), unit).withNano(0)

  fun randomInstant(): Instant = randomLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)

  fun randomAlphanumeric(len: Int): String = RandomStringUtils.secure().nextAlphanumeric(len)

  fun randomAlphabetic(len: Int): String = RandomStringUtils.secure().nextAlphabetic(len)

  fun randomUri(): String = "/${lorem.getWords(1).lowercase(Locale.getDefault())}}"

  fun randomTld(): String = randomListItem(tlds)

  fun randomUrl(protocol: String = "http"): String =
    "$protocol://${randomWords(1).lowercase(Locale.getDefault())}.${randomTld()}${randomUri()}"

  fun randomSlug(): String = randomAlphanumeric(12) + "-" + randomAlphanumeric(12) + "_" + randomAlphanumeric(12)

  fun randomTags(number: Int): List<String> = (0 until number).map { randomInt(0, Int.MAX_VALUE).toString() }

  fun <E : Enum<E>> randomEnum(enumClass: Class<E>): E = enumClass.enumConstants.random()

  fun <E> randomListItem(list: List<E>): E = list[randomInt(0, list.size - 1)]

  fun <T> randomElement(coll: Collection<T>): T = coll.random()

  fun randomFirstName(): String = lorem.firstName

  fun randomLastName(): String = lorem.lastName

  fun randomUUID(): String = UUID.randomUUID().toString()

  fun randomPhone(): String = lorem.phone

  fun randomEmail(): String = lorem.email

  // some random valid domain entities

  fun aValidAccount(
    accountTemplate: AccountTemplate,
    customerTranche: CustomerTranche? = null,
  ): Account =
    Account(
      accountId = UUID.randomUUID(),
      accountTemplate = accountTemplate,
      customerTranche = customerTranche,
    )

  fun aValidCustomerTranche(): CustomerTranche = CustomerTranche(customerTrancheId = UUID.randomUUID(), displayName = randomWords(4))

  fun aValidAccountTemplate(): AccountTemplate = AccountTemplate(accountTemplateId = randomUUID(), displayName = randomWords(3))

  fun aValidLedgerEntry(account: Account) =
    LedgerEntry(
      operationId = UUID.randomUUID(),
      account = account,
      type = randomEnum(LedgerEntryType::class.java),
      amount = randomBigDecimal(0.01, 999999.99),
      address = randomAlphabetic(16).uppercase(),
      asset = randomAlphabetic(16).uppercase(),
      timestamp = randomInstant(),
    )
}
