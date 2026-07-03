package iterator.nucleus.schedule

import com.fasterxml.jackson.databind.ObjectMapper
import iterator.nucleus.audit.AuditService
import iterator.nucleus.audit.ScheduledTaskFinishedEvent
import iterator.nucleus.audit.ScheduledTaskStartedEvent
import org.quartz.CronScheduleBuilder
import org.quartz.Job
import org.quartz.JobBuilder
import org.quartz.JobDataMap
import org.quartz.JobDetail
import org.quartz.JobExecutionContext
import org.quartz.JobExecutionException
import org.quartz.PersistJobDataAfterExecution
import org.quartz.Trigger
import org.quartz.TriggerBuilder
import org.quartz.spi.TriggerFiredBundle
import org.springframework.beans.factory.config.AutowireCapableBeanFactory
import org.springframework.boot.autoconfigure.quartz.QuartzProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.util.ProxyUtils
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import org.springframework.scheduling.quartz.SpringBeanJobFactory
import org.springframework.stereotype.Component
import java.util.TimeZone
import javax.sql.DataSource

data class ScheduledTaskDetails<T>(
  val beanClass: Class<out ScheduledTask<T>>,
  val cronExpression: String,
  val initialJobData: T,
  val dataClass: Class<T>,
)

@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> scheduledTask(
  bean: ScheduledTask<T>,
  cronExpression: String,
  initialJobData: T,
): ScheduledTaskDetails<T> =
  ScheduledTaskDetails(
    beanClass = ProxyUtils.getUserClass(bean.javaClass) as Class<out ScheduledTask<T>>,
    cronExpression = cronExpression,
    initialJobData = initialJobData,
    dataClass = T::class.java,
  )

interface ScheduledTask<T> {
  /**
   * Executes the task. Returning normally — with or without carry-forward
   * [ScheduledTaskResult.data] — is success by definition. To fail, throw: any exception is a
   * failure. Throw [ScheduledTaskException] to control whether Quartz refires immediately; any
   * other exception fails without an immediate refire.
   */
  fun run(data: T): ScheduledTaskResult<T>
}

class ScheduledTaskException(
  message: String,
  cause: Throwable,
  val refire: Boolean = false,
) : IllegalStateException(message, cause)

data class ScheduledTaskResult<T>(
  val data: T? = null,
)

enum class ScheduledTaskStatus {
  SUCCESS,
  FAILURE,
}

@Configuration
class ScheduledTaskConfiguration {
  @Bean
  fun jobDetails(
    tasks: List<ScheduledTaskDetails<*>>,
    om: ObjectMapper,
  ): List<JobDetail> =
    tasks.map {
      JobBuilder
        .newJob(QuartzScheduledJob::class.java)
        .withIdentity(it.beanClass.name, "scheduledTasks")
        .usingJobData(
          JobDataMap().apply {
            put("payloadClass", it.dataClass.name)
            put("payload", om.writeValueAsString(it.initialJobData))
          },
        ).storeDurably()
        .build()
    }

  @Bean
  fun triggers(tasks: List<ScheduledTaskDetails<*>>): List<Trigger> =
    tasks.map {
      TriggerBuilder
        .newTrigger()
        .forJob(it.beanClass.name, "scheduledTasks")
        .withIdentity("${it.beanClass.name}.trigger", "scheduledTasks")
        .withSchedule(
          CronScheduleBuilder
            .cronSchedule(it.cronExpression)
            .inTimeZone(TimeZone.getTimeZone("UTC")),
        ).build()
    }

  @Bean
  @Suppress("SpreadOperator")
  fun schedulerFactory(
    fac: AutowiringSpringBeanJobFactory,
    dataSource: DataSource,
    props: QuartzProperties,
    jobDetails: List<JobDetail>, // ← inject your job beans
    triggers: List<Trigger>,
  ): SchedulerFactoryBean =
    SchedulerFactoryBean().apply {
      setJobFactory(fac)
      setDataSource(dataSource)
      setQuartzProperties(props.properties.toProperties())
      setJobDetails(*jobDetails.toTypedArray())
      setTriggers(*triggers.toTypedArray())
    }
}

@Component
class AutowiringSpringBeanJobFactory(
  val fac: AutowireCapableBeanFactory,
) : SpringBeanJobFactory() {
  override fun createJobInstance(bundle: TriggerFiredBundle): Any {
    val job = super.createJobInstance(bundle)
    fac.autowireBean(job)
    return job
  }
}

@Component
@PersistJobDataAfterExecution
class QuartzScheduledJob(
  val ctx: ApplicationContext,
  val om: ObjectMapper,
  val audit: AuditService,
) : Job {
  @Suppress("UNCHECKED_CAST")
  override fun execute(jec: JobExecutionContext) {
    val start = System.currentTimeMillis()
    val name = jec.jobDetail.key.name
    audit.publishAuditEvent(ScheduledTaskStartedEvent(name))
    val data = jec.mergedJobDataMap
    try {
      val payloadClass = Class.forName(data.getString("payloadClass"))
      val payload = data.getString("payload")
      val taskData = om.readValue(payload, payloadClass)
      val taskClass = Class.forName(name)
      val task = ctx.getBean(taskClass) as ScheduledTask<Any>
      val result = task.run(taskData)
      data.put("payload", om.writeValueAsString(result.data))
      // Returning normally is success by definition.
      publishFinished(name, ScheduledTaskStatus.SUCCESS, start)
    } catch (e: ScheduledTaskException) {
      // A deliberate failure that controls whether Quartz refires immediately.
      publishFinished(name, ScheduledTaskStatus.FAILURE, start, e)
      throw JobExecutionException("Job $name failed", e, e.refire)
    } catch (e: Exception) {
      // Any other throw is a failure too, and always produces a finished event; Quartz does not
      // refire immediately unless a ScheduledTaskException asked it to.
      publishFinished(name, ScheduledTaskStatus.FAILURE, start, e)
      throw JobExecutionException("Job $name failed", e, false)
    }
  }

  private fun publishFinished(
    name: String,
    status: ScheduledTaskStatus,
    start: Long,
    error: Exception? = null,
  ) {
    audit.publishAuditEvent(
      ScheduledTaskFinishedEvent(
        taskName = name,
        status = status,
        executionDuration = measureDuration(start),
        error = error,
      ),
    )
  }

  private fun measureDuration(start: Long): Long = System.currentTimeMillis() - start
}
