package epoll

import scala.scalanative.libc.errno

sealed abstract class EpollError(msg: String) extends RuntimeException(msg)

final case class EpollCtlError(what: String)
    extends EpollError("an error occurred during epoll_ctl: " + what + " errno: " + errno.errno)

final case class EpollWaitError()
    extends EpollError("an error occurred during epoll_wait: errno: " + errno.errno)

final case class EpollCreateError()
    extends EpollError("an error occurred during epoll_create1: errno: " + errno.errno)

final case class EpollPipeError()
    extends EpollError("an error occurred during pipe initializing: errno: " + errno.errno)
