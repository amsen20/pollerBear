package pollerBear
package internal

import epoll._
import java.util.concurrent.Semaphore
import scala.collection.mutable
import scala.scalanative.posix.errno
import scala.scalanative.unsafe._

/**
 * A thread safe data structure for storing deadlines and their associated callbacks.
 */
class Deadlines[OnDeadline] {
  type Deadline = (Long, Long) // (time, unique id)
  @volatile private var maxId = 0L
  private val deadLines       = mutable.Set[Deadline]()
  private val IdToDeadline    = mutable.HashMap[Long, Deadline]()   // id -> deadline
  private val onDeadlines     = mutable.HashMap[Long, OnDeadline]() // id -> callback

  def getMinDeadline(): Option[(Deadline, OnDeadline)] =
    synchronized:
      if (deadLines.isEmpty) None
      else {
        val (minDeadline, id) = deadLines.min
        Some(((minDeadline, id), onDeadlines(id)))
      }

  def addDeadline(deadlineTime: Long, onDeadline: OnDeadline): Long =
    synchronized:
      val id = maxId
      maxId += 1

      val deadline: Deadline = (deadlineTime, id)

      deadLines.add(deadline)
      IdToDeadline.put(id, deadline)
      onDeadlines.put(id, onDeadline)

      id

  def removeDeadline(id: Long): Option[(Deadline, OnDeadline)] =
    synchronized:
      IdToDeadline.get(id) match
        case Some(deadline) =>
          deadLines.remove(deadline)
          IdToDeadline.remove(id)
          val onDeadline = onDeadlines.remove(id).get

          Some((deadline, onDeadline))
        case None => None

  def foreachCallback(f: OnDeadline => Unit): Unit =
    synchronized:
      onDeadlines.values.foreach(f)

  def clear(): Unit =
    synchronized:
      deadLines.clear()
      IdToDeadline.clear()
      onDeadlines.clear()

}

/**
 * A thread-safe queue that allows pushing while popping.
 * But the pushes won't be seen to the popper until they release are finished with popping.
 */
class PushWhilePoppingQueue[T] {
  private val queue = mutable.Queue[T]()

  // It grantees that when the buffer is not empty, there is another thread popping.
  // And after popping the thread will empty the buffer. No, elements will be left in the buffer.
  private val bufferQueue = mutable.Queue[T]()

  // the semaphore acts as a lock (found no simple lock that implements the Lock trait)
  private val readingLock = new Semaphore(1)

  @volatile private var threadIdPopping = -1L

  private def tryPush(elem: T): Boolean =
    if (readingLock.tryAcquire())
      queue.enqueue(elem)
      readingLock.release()
      true
    else false

  def push(elem: T): Unit =
    if !tryPush(elem) then
      bufferQueue.synchronized:
        if !tryPush(elem) then bufferQueue.enqueue(elem)

  /**
   * The method should be called only by the thread that is in the popping block.
   * In other cases, it will throw an IllegalStateException.
   */
  def pop(): Option[T] =
    if threadIdPopping != Thread.currentThread().threadId() then
      throw new IllegalStateException("Only the thread that is in popping block can pop")

    if queue.isEmpty then None
    else Some(queue.dequeue())

  def doPopping(body: => Unit): Unit =
    readingLock.acquireUninterruptibly()
    threadIdPopping = Thread.currentThread().threadId()

    try body
    finally
      bufferQueue.synchronized:
        while bufferQueue.nonEmpty do queue.enqueue(bufferQueue.dequeue())

      threadIdPopping = -1
      readingLock.release()

  def filterInPlace(f: T => Boolean): Unit =
    doPopping:
      queue.filterInPlace(f)

  def foreach(f: T => Unit): Unit =
    doPopping:
      queue.foreach(f)

  def clear(): Unit =
    doPopping:
      queue.clear()

}

class Fds[OnFd](epoll: Epoll) {

  private val onFds        = mutable.HashMap[Int, OnFd]() // fd -> callback
  private val expectFromFd = mutable.HashMap[Int, Long]() // fd -> expected events

  /**
   * @return The errno if the operation is not successful and the error is ignorable.
   */
  private def shallowAddFd(fd: Int, expectedEvents: EpollEvents): Option[CInt] =
    epoll.tryAddFd(fd, expectedEvents) match
      case Some(en) =>
        // The operation is happening by a delay so maybe the caller has closed the fd.
        // This will result in EBADF error that we can move on with.
        if en != errno.EBADF then throw RuntimeException(s"adding a fd, errno: ${en}")
        else Some(en)
      case None => None

  /**
   * @return The errno if the operation is not successful and the error is ignorable.
   */
  private def shallowRemoveFd(fd: Int): Option[CInt] =
    epoll.tryRemoveFd(fd) match
      case Some(en) =>
        // The same as `addFd` but epoll will automatically remove a closed fd.
        // So we can ignore ENOENT error as well.
        if en != errno.EBADF && en != errno.ENOENT then
          throw RuntimeException(s"removing a fd, errno: ${en}")
        else Some(en)
      case None => None

  /**
   * @return The errno if the operation is not successful and the error is ignorable.
   */
  private def shallowModifyFd(fd: Int, expectedEvents: EpollEvents): Option[CInt] =
    epoll.tryModifyFd(fd, expectedEvents) match
      case Some(en) =>
        // The same as `removeFd`
        if en != errno.EBADF && en != errno.ENOENT then
          throw RuntimeException(s"modifying a fd, errno: ${en}")
        else Some(en)
      case None => None

  /**
   * @return (is the fd added, the epoll errno)
   */
  def addOnFd(fd: Int, expectedEvents: EpollEvents, onFd: OnFd): (Boolean, Option[Int]) =
    synchronized:
      if !onFds.put(fd, onFd).isEmpty then (false, None)
      else
        // The fd is maybe closed before adding it (causing Some(en)).
        // So we ignore it and let the user to handle it.
        // Unlike epoll itself, poller tracks closed fds as well unless the user deletes them.
        val errnoOption = shallowAddFd(fd, expectedEvents)
        expectFromFd(fd) = expectedEvents.getMask().toLong

        (true, errnoOption)

  def getOnFd(fd: Int): Option[OnFd] =
    synchronized:
      onFds.get(fd)

  /**
   * @return (is the fd removed, the epoll errno)
   */
  def removeOnFd(fd: Int): (Boolean, Option[Int]) =
    synchronized:
      onFds.remove(fd) match
        case Some(_) =>
          val errnoOption = shallowRemoveFd(fd)
          expectFromFd.remove(fd)
          (true, errnoOption)
        case None => (false, None)

  /**
   * @return (was the fd in the set, is the expectation changed, the epoll errno)
   */
  def changeExpectation(fd: Int, expectedEvents: EpollEvents): (Boolean, Boolean, Option[Int]) =
    synchronized:
      expectFromFd.get(fd) match
        case Some(currentExpectMask) =>
          if currentExpectMask == expectedEvents.getMask().toLong then (true, false, None)
          else
            expectFromFd(fd) = expectedEvents.getMask().toLong
            val errnoOption = shallowModifyFd(fd, expectedEvents)
            (true, true, errnoOption)
        case None => (false, false, None)

  
  def foreachCallback(f: OnFd => Unit): Unit =
    synchronized:
      onFds.values.foreach(f)
  
  def clear(): Unit =
    synchronized:
      onFds.clear()
      expectFromFd.clear()
}
