package pollerBear
package runtime

import scalanative.unsafe._

sealed abstract class PollerBearError(msg: String) extends RuntimeException(msg)

final case class PollerBearEpollError(what: String, errno: CInt)
    extends PollerBearError("an error occurred during an epoll operation: " + what + " errno: " + errno)
