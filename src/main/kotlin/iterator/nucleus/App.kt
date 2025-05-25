package iterator.nucleus

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication class App

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
  runApplication<App>(*args)
}
