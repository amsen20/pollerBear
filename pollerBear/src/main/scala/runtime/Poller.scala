package pollerBear
package runtime

import epoll._

class PollerException              extends RuntimeException
final class PollerCleanUpException extends PollerException

trait Poller {

  /**
   * NOTE:
   * For all types of callbacks, if the callback returns false,
   * it will be removed from the poller.
   *
   * The callback should not throw an exception unless it wants to shut down the poller.
   *
   * The callback will be called with an error when an internal error occurs that stops the poller.
   *
   * All callbacks are called (executed) in the same thread that calls the `waitUntil` method.
   * Be careful to not block the thread and safely access other thread values.
   *
   * If the callbacks can read/write to values that other threads can also read/write to them,
   * the values should be accessed in a thread-safe way.
   *
   * When cleaning up the poller, the poller will call all the callbacks with a `PollerCleanUpException`.
   */
  type OnFd = Either[EpollEvents, Throwable] => Boolean

  /**
   * Each cycle is:
   * 1. Waiting for events (epoll_wait)
   * 2. Calling callbacks on each event (or for the timeout)
   * 3. Calling onCycle callbacks
   */
  type OnCycle    = Option[Throwable] => Boolean
  type OnDeadline = Option[Throwable] => Boolean
  type OnStart    = Option[Throwable] => Boolean
  type Action     = Option[Throwable] => Unit

  /**
   * Registers a callback to be called when an event happens on the given fd.
   */
  def registerOnFd(
      fd: Int,
      cb: OnFd,
      expectedEvents: EpollEvents
  ): Unit

  /**
   * Changes the expected events for the given fd.
   */
  def expectFromFd(
      fd: Int,
      expectedEvents: EpollEvents
  ): Unit

  /**
   * Removes the callback for the given fd.
   */
  def removeOnFd(fd: Int): Unit

  /**
   * Registers a callback to be called on each cycle.
   */
  def registerOnCycle(cb: OnCycle): Unit

  /**
   * Registers a callback to be called on the start of the poller.
   */
  def registerOnStart(cb: OnStart): Unit

  /**
   * Registers a callback to be called when the given deadline is reached.
   *
   * @return an unique id to remove the callback
   */
  def registerOnDeadline(
      deadLine: Long,
      cb: OnDeadline
  ): Long

  /**
   * Removes the callback for the given deadline.
   */
  def removeOnDeadline(id: Long): Unit

  /**
   * Execute an action in the poller thread.
   * If the caller has come from the poller thread, the action will be executed immediately.
   * Otherwise, the action will be registered as an one-time onCycle callback.
   */
  def runAction(action: Action): Unit

  /**
   * Wake up the poller from the `waitUntil` method.
   */
  def wakeUp(): Unit
}

trait PassivePoller extends Poller {

  /**
   * Waits until any event has happened or any deadline is reached.
   */
  def waitUntil(): Unit
}

trait ActivePoller extends Poller

/**
 * A passive poller does not poll periodically.
 * The caller have to call `waitUntil` frequently to proceed the poller.
 */
def withPassivePoller[T](maxEvents: Int)(body: PassivePoller => T): Unit =
  Epoll(maxEvents) { epoll =>
    val poller = new PollerImpl(epoll)
    try
      body(poller)
    finally
      poller.cleanUp()
  }

/**
 * An active poller polls periodically.
 */
def withActivePoller[T](maxEvents: Int)(body: ActivePoller ?=> T): Unit =
  Epoll(maxEvents) { epoll =>
    val poller = new PollerImpl(epoll)

    // TODO make the process more preemptive by making the cancellation
    // A process blended into inside the poller not from the outside of the poller.
    @volatile var isRunning                    = true
    @volatile var errOption: Option[Throwable] = None

    val pollerThread = new Thread(() =>
      try while isRunning do poller.waitUntil()
      // propagate the exception to the main thread
      catch case e: Throwable => errOption = Some(e)
      finally poller.cleanUp()
    )

    pollerThread.start()

    try
      body(
        using poller
      )
    finally
      isRunning = false
      poller.wakeUp()
      pollerThread.join()

      // throw the exception of the poller thread
      errOption.foreach(throw _)
  }
