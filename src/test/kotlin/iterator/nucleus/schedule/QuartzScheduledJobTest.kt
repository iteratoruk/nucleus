package iterator.nucleus.schedule

import iterator.nucleus.Serialization
import iterator.nucleus.TestingFu.randomAlphanumeric
import iterator.nucleus.TestingFu.randomBoolean
import iterator.nucleus.TestingFu.randomWords
import iterator.nucleus.audit.AbstractAuditEvent
import iterator.nucleus.audit.AuditService
import iterator.nucleus.audit.ScheduledTaskFinishedEvent
import iterator.nucleus.audit.ScheduledTaskStartedEvent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.given
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.quartz.JobBuilder
import org.quartz.JobDataMap
import org.quartz.JobExecutionContext
import org.quartz.JobExecutionException
import org.springframework.context.ApplicationContext
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class QuartzScheduledJobTest(
  @Mock val ctx: ApplicationContext,
  @Mock val audit: AuditService,
  @Mock val jec: JobExecutionContext,
) {
  val om = Serialization.mapper

  lateinit var job: QuartzScheduledJob

  @BeforeEach
  fun setup() {
    job = QuartzScheduledJob(ctx, om, audit)
  }

  @Test
  fun `should run task when execute job`() {
    // given
    val now = Instant.now()
    val str = randomAlphanumeric(16)
    val data = MockScheduledTaskData(str, now)
    val map =
      JobDataMap().apply {
        put("payloadClass", MockScheduledTaskData::class.java.name)
        put("payload", om.writeValueAsString(data))
      }
    given { jec.mergedJobDataMap }.willReturn(map)
    val detail =
      JobBuilder
        .newJob(QuartzScheduledJob::class.java)
        .withIdentity(MockScheduledTask::class.java.name, "scheduledTasks")
        .usingJobData(map)
        .build()
    given { jec.jobDetail }.willReturn(detail)
    val result =
      ScheduledTaskResult(
        status = ScheduledTaskStatus.SUCCESS,
        data = data.copy(now = now.plusMillis(1000), str = randomAlphanumeric(16)),
      )
    val task = MockScheduledTask(result)
    given { ctx.getBean(eq(MockScheduledTask::class.java)) }.willReturn(task)
    val before = System.currentTimeMillis()

    // when
    job.execute(jec)

    // then
    val duration = System.currentTimeMillis() - before
    assertThat(task.ran).isTrue
    assertThat(map.get("payload")).isEqualTo(om.writeValueAsString(result.data))
    val captor = argumentCaptor<AbstractAuditEvent>()
    verify(audit, times(2)).publishAuditEvent(captor.capture())
    assertThat((captor.firstValue as ScheduledTaskStartedEvent).taskName)
      .isEqualTo(MockScheduledTask::class.java.name)
    assertThat((captor.secondValue as ScheduledTaskFinishedEvent).taskName)
      .isEqualTo(MockScheduledTask::class.java.name)
    assertThat((captor.secondValue as ScheduledTaskFinishedEvent).status).isEqualTo(result.status)
    assertThat((captor.secondValue as ScheduledTaskFinishedEvent).error).isNull()
    assertThat((captor.secondValue as ScheduledTaskFinishedEvent).executionDuration)
      .isCloseTo(duration, Offset.offset(10))
  }

  @Test
  fun `throw audit failure and throw given failure in run task when execute job`() {
    // given
    val now = Instant.now()
    val str = randomAlphanumeric(16)
    val data = MockScheduledTaskData(str, now)
    val map =
      JobDataMap().apply {
        put("payloadClass", MockScheduledTaskData::class.java.name)
        put("payload", om.writeValueAsString(data))
      }
    given { jec.mergedJobDataMap }.willReturn(map)
    val detail =
      JobBuilder
        .newJob(QuartzScheduledJob::class.java)
        .withIdentity(MockScheduledTask::class.java.name, "scheduledTasks")
        .usingJobData(map)
        .build()
    given { jec.jobDetail }.willReturn(detail)
    val result =
      ScheduledTaskResult(
        status = ScheduledTaskStatus.SUCCESS,
        data = data.copy(now = now.plusMillis(1000), str = randomAlphanumeric(16)),
      )
    val exception =
      ScheduledTaskException(
        message = randomWords(8),
        cause = IllegalArgumentException(),
        refire = randomBoolean(),
      )
    val task = MockScheduledTask(result, exception)
    given { ctx.getBean(eq(MockScheduledTask::class.java)) }.willReturn(task)

    // when
    val actual = assertThrows<JobExecutionException> { job.execute(jec) }

    // then
    assertThat(task.ran).isTrue
    assertThat(actual.refireImmediately()).isEqualTo(exception.refire)
    assertThat(actual.cause).isEqualTo(exception)
    // the job data map should not be updated
    assertThat(map.get("payload")).isEqualTo(om.writeValueAsString(data))
    val captor = argumentCaptor<AbstractAuditEvent>()
    verify(audit, times(2)).publishAuditEvent(captor.capture())
    assertThat((captor.firstValue as ScheduledTaskStartedEvent).taskName)
      .isEqualTo(MockScheduledTask::class.java.name)
    assertThat((captor.secondValue as ScheduledTaskFinishedEvent).taskName)
      .isEqualTo(MockScheduledTask::class.java.name)
    assertThat((captor.secondValue as ScheduledTaskFinishedEvent).status)
      .isEqualTo(ScheduledTaskStatus.FAILURE)
    assertThat((captor.secondValue as ScheduledTaskFinishedEvent).error).isEqualTo(exception)
  }
}

class MockScheduledTask(
  val result: ScheduledTaskResult<MockScheduledTaskData>,
  val exception: ScheduledTaskException? = null,
) : ScheduledTask<MockScheduledTaskData> {
  var ran = false

  override fun run(data: MockScheduledTaskData): ScheduledTaskResult<MockScheduledTaskData> {
    ran = true
    if (exception != null) throw exception
    return result
  }
}

data class MockScheduledTaskData(
  val str: String,
  val now: Instant,
)
