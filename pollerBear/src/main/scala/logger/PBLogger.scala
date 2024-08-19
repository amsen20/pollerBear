package pollerBear
package logger

// TODO:
// - Add multiple log levels
// - Add a way to log to a file
inline private val enableLogging = false

final private val SECOND = 1000
final private val MINUTE = 60 * SECOND
final private val HOUR   = 60 * MINUTE

object PBLogger:

  inline def log(msg: String): Unit =
    inline if enableLogging then
      print("[gURL] ")
      print(s"[${Thread.currentThread().getName()}] ")
      val time = System.currentTimeMillis() % HOUR
      print(s"[${time / MINUTE}:${time % MINUTE / SECOND}:${time % SECOND}] ")
      println(msg)
