package pollerBear
package runtime

import epoll._
import pollerBear.internal._
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

  // TODO rethink this concept and maybe change it to `is cleaning up?` and otherwise directly throw an exception.
  // TODO when there is any error during the methods
  // The reason why the scheduler cannot continue working (is in corrupted state)
  @volatile private var reason = Option.empty[Throwable]

  private val fds = Fds[OnFd](epoll)

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
        fds.getOnFd(waitEvent.fd) match
          case Some(cb) =>
            if (!cb(Left(waitEvent.events))) {
              PBLogger.log(s"callback discontinued for fd ${waitEvent.fd}")
              fds.removeOnFd(waitEvent.fd)
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

    start()

    try poll()
    catch {
      case e =>
        PBLogger.log(s"an exception caught while polling: ${e.getMessage()}")
        reason = Some(e)
        throw e
    }

    onCycles.filterInPlace(_(None))

    pollerThreadId = -1L

    PBLogger.log("done waiting...")

  override def registerOnFd(
      fd: Int,
      cb: OnFd,
      expectedEvents: EpollEvents
  ): Unit =
    checkIsSafe()

    PBLogger.log("adding a callback for a fd...")

    val (isAdded, errOption) = fds.addOnFd(fd, expectedEvents, cb)

    if isAdded then PBLogger.log(s"a callback already exists for fd ${fd}")
    else PBLogger.log(s"callback added for fd ${fd}")

    if errOption.isDefined then
      PBLogger.log(s"(shallow, ignoring) failed to add a callback for fd ${fd}")

  override def expectFromFd(
      fd: Int,
      expectedEvents: EpollEvents
  ): Unit =
    checkIsSafe()
    PBLogger.log(s"modifying the expected events for a fd ${fd}...")

    val (isInFdSet, didChanged, errnoOption) = fds.changeExpectation(fd, expectedEvents)

    if !isInFdSet then PBLogger.log(s"no callback found for fd ${fd} to change the expectation")

    if didChanged then
      PBLogger.log(s"expectation changed for fd ${fd} to ${expectedEvents.getMask().toLong}")
    else PBLogger.log(s"expectation is the same for fd ${fd}")

    if errnoOption.isDefined then
      PBLogger.log(
        s"(shallow, ignoring) failed to modify the expected events for fd ${fd}"
      )

  override def removeOnFd(fd: Int): Unit =
    checkIsSafe()
    PBLogger.log("removing a callback for a fd...")
    val (didRemoved, errnoOption) = fds.removeOnFd(fd)

    if didRemoved then PBLogger.log(s"callback removed for fd ${fd}")
    else PBLogger.log(s"no callback found for fd ${fd}")

    if errnoOption.isDefined then
      // Ignoring the result, because it is removed from epoll automatically.
      PBLogger.log(s"(shallow, ignoring) failed to remove a callback for fd ${fd}")

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

    if reason.isEmpty then reason = Some(new PollerCleanUpException())

    // Inform their callbacks that the polling is stopped.
    PBLogger.log("informing callbacks onFds...")
    fds.foreachCallback(_(Right(reason.get)))
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

    // clear the callbacks
    fds.clear()
    onCycles.clear()
    onStarts.clear()

    // clear deadlines
    deadlines.clear()

    PBLogger.log("cleaned up poller bear")

}
