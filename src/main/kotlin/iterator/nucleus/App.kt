package iterator.nucleus

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@SpringBootApplication
class App : WebMvcConfigurer {
  companion object {
    @JvmStatic
    @Suppress("SpreadOperator")
    fun main(args: Array<String>) {
      runApplication<App>(*args)
    }
  }
}
