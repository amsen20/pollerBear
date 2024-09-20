package pollerBear
package logger

// TODO:
// - Add multiple log levels
// - Add a way to log to a file
inline private val enableLogging = false

final private val MICRO  = 1000L
final private val MILLI  = 1000 * MICRO
final private val SECOND = 1000 * MILLI
final private val MINUTE = 60 * SECOND
final private val HOUR   = 60 * MINUTE

object PBLogger:

  inline def log(msg: String): Unit =
    inline if enableLogging then
      print("[PB] ")
      print(s"[${Thread.currentThread().getName()}] ")
      val time = System.nanoTime() % HOUR
      print(
        s"[${time / MINUTE}:${time % MINUTE / SECOND}:${time % SECOND / MILLI}.${time % MILLI / MICRO}] "
      )
      println(msg)
