package pollerBear
package epoll

import scala.scalanative.posix.errno
import scalanative.unsafe._

sealed abstract class EpollError(msg: String) extends RuntimeException(msg)

final case class EpollInitiationError(what: String, errno: CInt)
    extends EpollError(
      "an error occurred during epoll initialization: " + what + " errno: " + errno
    )

final case class EpollCtlError(what: String, errno: CInt)
    extends EpollError("an error occurred during epoll_ctl: " + what + " errno: " + errno)

final case class EpollWaitError()
    extends EpollError("an error occurred during epoll_wait: errno: " + errno.errno)

final case class EpollCreateError()
    extends EpollError("an error occurred during epoll_create1: errno: " + errno.errno)

final case class EpollPipeError()
    extends EpollError("an error occurred during pipe initializing: errno: " + errno.errno)
