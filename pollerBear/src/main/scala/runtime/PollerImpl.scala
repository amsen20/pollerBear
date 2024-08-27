package pollerBear
package runtime

import epoll._
import pollerBear.internal.Utils
import pollerBear.logger.PBLogger
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.scalanative.posix.errno
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

/**
 * A simple poller that uses epoll to wait for events.
 * FDs can be added and removed during its execution.
 * NOTE: closed fds are tracked as well (unlike epoll), so the user remove them.
 *
 * @param epoll The epoll instance.
 */
final private class PollerImpl(
    epoll: Epoll
) extends PassivePoller {

  // The reason why the scheduler cannot continue working (is in corrupted state)
  @volatile private var reason = Option.empty[Throwable]

  // Shared state between multiple threads:
  /**
   * A modification in state of the poller.
   * Like adding, removing, or modifying a callback.
   */
  private type Modification = (() => Unit)

  /**
   * A queue of modifications in the state of the poller.
   * For pushing and popping the object's lock should be acquired.
   */
  private val modifications = mutable.ListBuffer.empty[(Modification, AfterModification)]

  private val onFds        = mutable.HashMap[Int, OnFd]()
  private val expectFromFd = mutable.HashMap[Int, Long]() // fd -> expected events

  private val onCycles = mutable.ArrayBuffer[OnCycle]()

  type Deadline = (Long, Long) // (time, unique id)
  @volatile private var maxId = 0L
  private val deadLines       = mutable.Set[Deadline]()
  private val IdToDeadline    = mutable.HashMap[Long, Deadline]()   // id -> deadline
  private val onDeadlines     = mutable.HashMap[Long, OnDeadline]() // id -> callback

  private val onStarts = mutable.ArrayBuffer[OnStart]()

  /**
   * Checks if the runtime is in a corrupted state
   * and throws an exception representing the reason if it is.
   */
  private def checkIsSafe(): Unit =
    if reason.isDefined then
      PBLogger.log("tried to do an operation, but the poller is in corrupted state")
      throw reason.get

  /**
   * @return The errno if the operation is not successful and the error is ignorable.
   */
  private def shallowAddFd(fd: Int, expectedEvents: EpollEvents): Option[CInt] =
    epoll.tryAddFd(fd, expectedEvents) match
      case Some(en) =>
        // The operation is happening by a delay so maybe the caller has closed the fd.
        // This will result in EBADF error that we can move on with.
        if en != errno.EBADF then throw PollerBearEpollError("adding a fd", en)
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
          throw PollerBearEpollError("removing a fd", en)
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
          throw PollerBearEpollError("modifying a fd", en)
        else Some(en)
      case None => None

  /**
   * Polls the events and processes them.
   */
  private def poll(): Unit =

    val (timeout, onTimeout) =
      if deadLines.isEmpty then (-1L, None)
      else
        val now                   = System.currentTimeMillis()
        val (nearestDeadline, id) = deadLines.min
        val timeToDeadline        = nearestDeadline - now
        val on                    = onDeadlines(id)

        removeOnDeadline(id)

        (Math.max(0, timeToDeadline), Some(on))

    if (!onFds.isEmpty || timeout >= 0) {
      PBLogger.log("entering epolling...")
      val (events, didTimeout) = epoll.waitForEvents(timeout.toInt)
      PBLogger.log("waken up from polling...")

      if (didTimeout) {
        PBLogger.log("timeout happened")
        onTimeout.foreach(_(None))
      } else {
        PBLogger.log("some events happened")
        events.foreach { waitEvent =>
          PBLogger.log("processing an event...")

          // Be aware of performance problems here!
          // The time complexity is O(#events * log(#registered fds)).
          onFds.get(waitEvent.fd) match
            case Some(cb) =>
              if (!cb(Left(waitEvent.events))) {
                PBLogger.log(s"callback discontinued for fd ${waitEvent.fd}")
                onFds.remove(waitEvent.fd)
                expectFromFd.remove(waitEvent.fd)
                shallowRemoveFd(waitEvent.fd)
              }
            case None =>
              // The only case that this happens is that between the epoll_wait and processing this event,
              // processing another event caused this fd to be removed.
              // So they have already been removed from epoll as well.
              // No need for doing anything else.
              PBLogger.log(s"have event on some fd but no callback found for fd ${waitEvent.fd}")
        }
      }
    }

  private def start(): Unit =
    PBLogger.log("starting the poller...")
    onStarts.filterInPlace(_(None))

  override def waitUntil(): Unit =
    checkIsSafe()

    PBLogger.log(s"waiting Until...")

    processModifications()

    // TODO consider processing modifications after each onStart execution
    start()

    processModifications()

    try poll()
    catch {
      case e =>
        PBLogger.log(s"an exception caught while polling: ${e.getMessage()}")
        reason = Some(e)
        throw e
    }

    processModifications()

    // TODO: consider processing modifications after each onCycle execution
    onCycles.filterInPlace(_(None))
    PBLogger.log("done waiting...")

  private def dispatchModification(modification: Modification, after: AfterModification): Unit =
    modifications.synchronized:
      modifications.addOne((modification, after))

  private def processModifications(): Unit =
    modifications.synchronized:
      modifications.foreach((modification, after) =>
        try {
          modification()
          after(None)
        } catch case e: Throwable => after(Some(e))
      )
      modifications.clear()

  override def registerOnFd(
      fd: Int,
      cb: OnFd,
      expectedEvents: EpollEvents,
      after: AfterModification = defaultAfterModification
  ): Unit =
    dispatchModification(
      () =>
        checkIsSafe()
        PBLogger.log("adding a callback for a fd...")

        if !onFds.put(fd, cb).isEmpty then PBLogger.log(s"a callback already exists for fd ${fd}")
        else
          PBLogger.log(s"callback added for fd ${fd}")
          // The fd is maybe closed before adding it (causing Some(en)).
          // So we ignore it and let the user to handle it.
          // Unlike epoll itself, poller tracks closed fds as well unless the user deletes them.
          shallowAddFd(fd, expectedEvents) match
            case Some(en) =>
              PBLogger.log(s"(shallow, ignoring) failed to add a callback for fd ${fd}")
            case None => ()

          expectFromFd(fd) = expectedEvents.getMask().toLong
      ,
      after
    )

  override def expectFromFd(
      fd: Int,
      expectedEvents: EpollEvents,
      after: AfterModification = defaultAfterModification
  ): Unit =
    dispatchModification(
      () =>
        val currentExpectationFromFdOption = expectFromFd.get(fd)
        val currentExpectMask = currentExpectationFromFdOption.map(_.toLong).getOrElse(-1L)
        // TODO change it to a poller bear
        if currentExpectMask != -1 then
          val newExpectMask = expectedEvents.getMask().toLong
          if currentExpectMask != newExpectMask then
            expectFromFd(fd) = newExpectMask
            shallowModifyFd(fd, expectedEvents) match
              case Some(en) =>
                // Ignoring it, because it is removed from epoll automatically.
                PBLogger.log(
                  s"(shallow, ignoring) failed to modify the expected events for fd ${fd}"
                )
              case None => ()
      ,
      after
    )

  override def removeOnFd(fd: Int, after: AfterModification = defaultAfterModification): Unit =
    dispatchModification(
      () =>
        checkIsSafe()
        PBLogger.log("removing a callback for a fd...")

        if onFds.remove(fd).isEmpty then PBLogger.log(s"no callback found for fd ${fd}")
        else
          PBLogger.log(s"callback removed for fd ${fd}")
          shallowRemoveFd(fd) match
            case None        => ()
            case Some(value) =>
              // Ignoring the result, because it is removed from epoll automatically.
              PBLogger.log(s"(shallow, ignoring) failed to remove a callback for fd ${fd}")

          expectFromFd.remove(fd)
      ,
      after
    )

  override def registerOnCycle(
      cb: OnCycle,
      after: AfterModification = defaultAfterModification
  ): Unit =
    dispatchModification(
      () =>
        checkIsSafe()
        PBLogger.log("adding a callback for a cycle...")

        onCycles += cb
      ,
      after
    )

  override def registerOnStart(
      cb: OnStart,
      after: AfterModification = defaultAfterModification
  ): Unit =
    dispatchModification(
      () =>
        checkIsSafe()
        PBLogger.log("adding a callback for a start...")

        onStarts += cb
      ,
      after
    )

  override def registerOnDeadline(
      deadLine: Long,
      cb: OnDeadline,
      after: AfterModification = defaultAfterModification
  ): Long =
    PBLogger.log("increasing the maxId...")
    val id = maxId
    maxId += 1

    dispatchModification(
      () =>
        checkIsSafe()
        PBLogger.log("adding a callback for a deadline...")

        val deadLineObj: Deadline = (deadLine, id)
        deadLines += deadLineObj
        IdToDeadline(id) = deadLineObj
        onDeadlines(id) = cb
      ,
      after
    )

    id

  override def removeOnDeadline(
      id: Long,
      after: AfterModification = defaultAfterModification
  ): Unit =
    dispatchModification(
      () =>
        checkIsSafe()
        PBLogger.log("removing a callback for a deadline...")

        if onDeadlines.remove(id).isEmpty then PBLogger.log(s"no callback found for deadline ${id}")
        else
          PBLogger.log(s"callback removed for deadline ${id}")
          val deadLine = IdToDeadline(id)
          deadLines.remove(deadLine)
          IdToDeadline.remove(id)
      ,
      after
    )

  /**
   * Cleans up all the callbacks
   */
  private[pollerBear] def cleanUp(): Unit =
    if reason.isEmpty then reason = Some(new PollerCleanUpException())

    // Inform their callbacks that the polling is stopped.
    PBLogger.log("informing callbacks onFds...")
    onFds.foreach((_, cb) => cb(Right(reason.get)))
    PBLogger.log("informing callbacks onCycles...")
    onCycles.foreach(_(Some(reason.get)))
    PBLogger.log("informing callbacks onStarts...")
    onStarts.foreach(_(Some(reason.get)))
    PBLogger.log("informing callbacks onDeadlines...")
    onDeadlines.foreach((_, cb) => cb(Some(reason.get)))

    // Clearing the callbacks.
    onFds.clear()
    onCycles.clear()
    onStarts.clear()
    onDeadlines.clear()

    PBLogger.log("cleaned up poller bear")

}
