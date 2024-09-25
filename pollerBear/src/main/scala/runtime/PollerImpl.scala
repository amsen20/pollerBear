package pollerBear
package runtime

import epoll._
import pollerBear.internal.Deadlines
import pollerBear.internal.PushWhilePoppingQueue
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
) extends PassivePoller
    with ActivePoller {

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
  private val modifications = mutable.Queue.empty[(Modification, AfterModification)]

  private val onFds        = mutable.HashMap[Int, OnFd]()
  private val expectFromFd = mutable.HashMap[Int, Long]() // fd -> expected events

  private val deadlines = new Deadlines[OnDeadline]()

  private val onCycles = PushWhilePoppingQueue[OnCycle]()
  private val onStarts = PushWhilePoppingQueue[OnStart]()

  @volatile private var pollerThreadId = -1L

  /**
   * Check if the caller has come from the poller itself.
   */
  private def isItFromThePoller(): Boolean =
    val currentThreadId = Thread.currentThread().threadId()
    PBLogger.log(
      s"is it from the poller? (current thread id: ${currentThreadId}, poller thread id: ${pollerThreadId})"
    )

    currentThreadId == pollerThreadId

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
    val now = System.currentTimeMillis()

    val (timeout, onTimeout, timeoutId) = deadlines.getMinDeadline() match
      case Some(((deadline, id), on)) =>
        val timeToDeadline = deadline - now
        (Math.max(0, timeToDeadline), Some(on), id)
      case None => (1000L, None, -1L)

    PBLogger.log(s"entering epolling (timeout: ${timeout.toInt})...")
    val (events, didTimeout) = epoll.waitForEvents(timeout.toInt)
    PBLogger.log("waken up from polling...")

    if (didTimeout) {
      PBLogger.log("timeout happened")
      removeOnDeadline(timeoutId)
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

  private def start(): Unit =
    PBLogger.log("starting the poller...")
    onStarts.filterInPlace(_(None))

  override def waitUntil(): Unit =
    checkIsSafe()

    pollerThreadId = Thread.currentThread().threadId()

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

    pollerThreadId = -1L

    PBLogger.log("done waiting...")

  private def dispatchModification(modification: Modification, after: AfterModification): Unit =
    modifications.synchronized:
      modifications.enqueue((modification, after))

  private def processModifications(): Unit =
    modifications.synchronized:
      while modifications.nonEmpty do
        val (modification, after) = modifications.dequeue()
        try {
          modification()
          after(None)
        } catch case e: Throwable => after(Some(e))

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
        PBLogger.log(s"modifying the expected events for a fd ${fd}...")
        val currentExpectationFromFdOption = expectFromFd.get(fd)
        val currentExpectMask = currentExpectationFromFdOption.map(_.toLong).getOrElse(-1L)
        // TODO change it to a poller bear
        if currentExpectMask != -1 then
          val newExpectMask = expectedEvents.getMask().toLong
          if currentExpectMask != newExpectMask then
            PBLogger.log(
              s"expectation changed for fd ${fd} from ${currentExpectMask} to ${newExpectMask}"
            )
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
      cb: OnCycle
  ): Unit =
    checkIsSafe()
    PBLogger.log("adding a callback for a cycle...")
    onCycles.push(cb)

  override def registerOnStart(
      cb: OnStart
  ): Unit =
    checkIsSafe()
    PBLogger.log("adding a callback for a start...")
    onStarts.push(cb)

  override def registerOnDeadline(
      deadline: Long,
      cb: OnDeadline
  ): Long =
    checkIsSafe()

    PBLogger.log("adding a callback for a deadline...")
    deadlines.addDeadline(deadline, cb)

  override def removeOnDeadline(
      id: Long
  ): Unit =
    checkIsSafe()
    PBLogger.log("removing a callback for a deadline...")

    if deadlines.removeDeadline(id).isEmpty then
      PBLogger.log(s"no callback found for deadline ${id}")
    else PBLogger.log(s"callback removed for deadline ${id}")

  override def runAction(action: Action): Unit =
    if isItFromThePoller() then
      PBLogger.log("running an action immediately")
      action(reason)
    else
      PBLogger.log("registering an action as a one-time onCycle callback")
      onCycles.push(errOption =>
        action(errOption)
        false
      )

  override def wakeUp(): Unit = epoll.wakeUp()

  /**
   * Cleans up all the callbacks
   */
  private[pollerBear] def cleanUp(): Unit =
    // Do all the modification before cleaning up.
    processModifications()

    if reason.isEmpty then reason = Some(new PollerCleanUpException())

    // Inform their callbacks that the polling is stopped.
    PBLogger.log("informing callbacks onFds...")
    onFds.foreach((_, cb) => cb(Right(reason.get)))
    PBLogger.log("informing callbacks onCycles...")
    // ! FIXME: There is scenario that a callback is not called
    // The method checks for reason and finds nothing and then it is preempted by cleanUp.
    // Then the reason is set and callbacks are informed and cleared.
    // Then the method continues and the callback is added finely but not not called.
    onCycles.foreach(_(Some(reason.get)))
    PBLogger.log("informing callbacks onStarts...")
    onStarts.foreach(_(Some(reason.get)))
    PBLogger.log("informing callbacks onDeadlines...")
    deadlines.foreachCallback(_(Some(reason.get)))
    PBLogger.log("informing callbacks AfterModifications...")
    modifications.foreach((_, after) => after(Some(reason.get)))

    // clear the callbacks
    onFds.clear()
    onCycles.clear()
    onStarts.clear()
    modifications.clear()

    // clear deadlines
    deadlines.clear()

    PBLogger.log("cleaned up poller bear")

}
