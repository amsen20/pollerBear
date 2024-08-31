package pollerBear
package epoll

import scala.scalanative.posix.errno
import scalanative.posix.fcntl
import scalanative.unsafe._

object Utils {

  def isFdValid(fd: CInt): Boolean =
    fcntl.fcntl(fd, fcntl.F_GETFD, 0) != -1 || errno.errno != errno.EBADF

  /**
   * @return Some(errno) if failed, None otherwise
   */
  def setNonBlocking(fd: CInt): Option[CInt] = {
    val flags = fcntl.fcntl(fd, fcntl.F_GETFL, 0)
    if (flags != -1) {
      val ret = fcntl.fcntl(fd, fcntl.F_SETFL, flags | fcntl.O_NONBLOCK)
      if (ret == -1) Some(errno.errno)
      else None
    } else Some(errno.errno)
  }

}
