package pollerBear
package epoll

// TODO: test it!!

import epollImplicits._
import pollerBear.logger.PBLogger
import scala.collection.mutable._
import scala.scalanative.posix.errno
import scala.scalanative.posix.unistd._
import scala.scalanative.runtime._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

case class WaitEvent(fd: Int, events: EpollEvents)

class Epoll(epollFd: Int, maxEvents: Int)(
    using Zone
) {

  // dummy pipe fds is for waking up epoll_wait
  val dummyPipeFds                  = alloc[Int](2) // [read, write]
  val readDummyBuf                  = alloc[Byte](1)
  val writeDummyBuf                 = alloc[Byte](1)
  @volatile var is_going_to_wake_up = false

  // the event object used for epoll_ctl
  val ev = alloc[epoll.epoll_event]()
  // the events array used for epoll_wait
  val evs = alloc[epoll.epoll_event](maxEvents)

  def initDummyPipe(): Unit =
    if pipe(dummyPipeFds) != 0 then throw EpollCreateError()
    Utils.setNonBlocking(dummyPipeFds(0)).foreach { errno =>
      throw EpollInitiationError("setting pipe read fd to non-blocking", errno)
    }
    Utils.setNonBlocking(dummyPipeFds(1)).foreach { errno =>
      throw EpollInitiationError("setting pipe write fd to non-blocking", errno)
    }
    addFd(dummyPipeFds(0), EpollInputEvents().wakeUp().input())

  initDummyPipe()

  private def epollCtlSafe(fd: Int, what: Int, op: EpollEvents): Option[CInt] =
    val msk = op.getMask()
    ev.events = msk.toUInt
    ev.data = fd.toPtr[Byte]

    if epoll.epoll_ctl(epollFd, what, fd, ev) != 0 then Some(errno.errno)
    else None

  private def epollCtl(fd: Int, what: Int, op: EpollEvents): Unit =
    epollCtlSafe(fd, what, op) match
      case Some(errno) =>
        throw EpollCtlError(
          s"failed to ${what match
              case epoll.EPOLL_CTL_ADD => "add"
              case epoll.EPOLL_CTL_MOD => "modify"
              case epoll.EPOLL_CTL_DEL => "delete"
            } fd: ${fd} from epoll",
          errno
        )
      case None => ()

  def addFd(fd: Int, op: EpollEvents): Unit =
    epollCtl(fd, epoll.EPOLL_CTL_ADD, op)

  def tryAddFd(fd: Int, op: EpollEvents): Option[CInt] =
    epollCtlSafe(fd, epoll.EPOLL_CTL_ADD, op)

  def modifyFd(fd: Int, op: EpollEvents): Unit =
    epollCtl(fd, epoll.EPOLL_CTL_MOD, op)

  def tryModifyFd(fd: Int, op: EpollEvents): Option[CInt] =
    epollCtlSafe(fd, epoll.EPOLL_CTL_MOD, op)

  def removeFd(fd: Int): Unit =
    epollCtl(fd, epoll.EPOLL_CTL_DEL, EpollEvents())

  def tryRemoveFd(fd: Int): Option[CInt] =
    epollCtlSafe(fd, epoll.EPOLL_CTL_DEL, EpollEvents())

  def waitForEvents(timeout: Int): (List[WaitEvent], Boolean) =
    val nfds = epoll.epoll_wait(epollFd, evs, maxEvents, timeout)
    synchronized:
      is_going_to_wake_up = false

    if nfds < 0 then throw EpollWaitError()
    val waitEvents = ListBuffer.empty[WaitEvent]
    for i <- 0 until nfds do
      val curEvent  = evs + i
      val curFd     = curEvent.data.toInt
      val curEvents = curEvent.events.toInt
      if curFd == dummyPipeFds(0) then
        // drain the dummy pipe
        read(dummyPipeFds(0), readDummyBuf, 1.toCSize)
      else waitEvents += WaitEvent(curFd, EpollEvents.fromMask(curEvents))

    (waitEvents.toList, nfds == 0)

  def wakeUp(): Unit =
    synchronized:
      if !is_going_to_wake_up then
        is_going_to_wake_up = true
        write(dummyPipeFds(1), writeDummyBuf, 1.toCSize)

}

object Epoll {

  def apply[T](maxEvents: Int = 64)(body: Epoll => T): T =
    Zone:
      val epollFd = epoll.epoll_create1(0)
      if epollFd < 0 then throw EpollCreateError()
      try
        body(new Epoll(epollFd, maxEvents))
      finally close(epollFd)

}
