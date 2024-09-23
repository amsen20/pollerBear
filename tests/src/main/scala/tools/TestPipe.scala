package tests
package tools

import pollerBear.epoll.EpollInputEvents
import pollerBear.epoll.Utils
import pollerBear.runtime._
import scala.collection.mutable._
import scala.scalanative.libc
import scala.scalanative.posix.errno
import scala.scalanative.posix.unistd
import scala.scalanative.runtime._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

/**
 * A thread safe pipe
 * One reader at a time
 * One writer at a time
 * Reader and writer can work in parallel
 */
class TestPipe(bufferSize: Int)(
    using Zone
) {
  val fds = alloc[Int](2) // [read, write]

  val readLockObj = new Object
  val readBuf     = alloc[Byte](bufferSize)

  val writeLockObj = new Object
  val writeBuf     = alloc[Byte](bufferSize)

  private def init(): Unit =
    if unistd.pipe(fds) != 0 then throw RuntimeException("failed to create pipe")
    Utils.setNonBlocking(fds(0)).foreach { errno =>
      throw RuntimeException(s"failed setting pipe read fd to non-blocking ${errno}")
    }
    Utils.setNonBlocking(fds(1)).foreach { errno =>
      throw RuntimeException(s"failed setting pipe write fd to non-blocking ${errno}")
    }

  init()

  def read(): String =
    readLockObj.synchronized:
      val n = unistd.read(fds(0), readBuf, bufferSize.toCSize)
      if n < 0 then throw RuntimeException("failed to read from pipe")

      fromCString(readBuf)

  def write(msg: String): Unit =
    writeLockObj.synchronized:
      val cstr = toCString(msg)
      val n    = unistd.write(fds(1), cstr, libc.string.strlen(cstr))
      if n < 0 then throw RuntimeException("failed to write to pipe")
      if n != libc.string.strlen(cstr) then throw RuntimeException("failed to write all bytes")

}
