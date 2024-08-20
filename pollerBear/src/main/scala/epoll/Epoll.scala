package pollerBear
package epoll

// TODO: test it!!

import scala.scalanative.unsafe._
import scala.scalanative.runtime._
import scala.scalanative.unsigned._
import scala.scalanative.posix.unistd._
import scala.collection.mutable._

import epollImplicits._

case class WaitEvent(fd: Int, events: EpollEvents)

class Epoll(epollFd: Int, maxEvents: Int)(using Zone) {

  // dummy pipe fds is for waking up epoll_wait
  val dummyPipeFds = alloc[Int](2)
  val dummyBuf = alloc[Byte](1)

  // the event object used for epoll_ctl
  val ev = alloc[epoll.epoll_event]()
  // the events array used for epoll_wait
  val evs = alloc[epoll.epoll_event](maxEvents)

  def initDummyPipe(): Unit =
    if pipe(dummyPipeFds) != 0 then throw EpollCreateError()
    addFd(dummyPipeFds(0), EpollInputEvents().wakeUp().input())

  initDummyPipe()

  private def epollCtl(fd: Int, what: Int, op: EpollEvents): Unit =
    val msk = op.getMask()
    ev.events = msk.toUInt
    ev.data = fd.toPtr[Byte]

    // TODO check the fd flags
    if epoll.epoll_ctl(epollFd, what, fd, ev) != 0 then
      throw EpollCtlError(s"failed to ${what match
          case epoll.EPOLL_CTL_ADD => "add"
          case epoll.EPOLL_CTL_MOD => "modify"
          case epoll.EPOLL_CTL_DEL => "delete"
        } fd: ${fd} from epoll")

  def addFd(fd: Int, op: EpollEvents): Unit =
    epollCtl(fd, epoll.EPOLL_CTL_ADD, op)

  def modifyFd(fd: Int, op: EpollEvents): Unit =
    epollCtl(fd, epoll.EPOLL_CTL_MOD, op)

  def removeFd(fd: Int): Unit =
    epollCtl(fd, epoll.EPOLL_CTL_DEL, EpollEvents())

  def waitForEvents(timeout: Int): (List[WaitEvent], Boolean) =
    val nfds = epoll.epoll_wait(epollFd, evs, maxEvents, timeout)
    if nfds < 0 then throw EpollWaitError()
    val waitEvents = ListBuffer.empty[WaitEvent]
    for i <- 0 until nfds do
      val curEvent = evs + i
      val curFd = curEvent.data.toInt
      val curEvents = curEvent.events.toInt
      if curFd == dummyPipeFds(0) then
        // drain the dummy pipe
        read(dummyPipeFds(0), dummyBuf, 1.toCSize)
      else waitEvents += WaitEvent(curFd, EpollEvents.fromMask(curEvents))

    (waitEvents.toList, nfds == 0)

  def wakeUp(): Unit =
    write(dummyPipeFds(1), dummyBuf, 1.toCSize)
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
