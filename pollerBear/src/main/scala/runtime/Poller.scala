package pollerBear
package runtime

import epoll._

class PollerException              extends RuntimeException
final class PollerCleanUpException extends PollerException

/**
 * TODO For now the API methods return values are erased.
 * Consider adding a callback that will be called with the return value of
 * each API method.
 */

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
   */
  type onFd = Either[EpollEvents, Throwable] => Boolean

  /**
   * Each cycle is:
   * 1. Waiting for events (epoll_wait)
   * 2. Calling callbacks on each event (or for the timeout)
   * 3. Calling onCycle callbacks
   */
  type onCycle    = Option[Throwable] => Boolean
  type onDeadline = Option[Throwable] => Boolean
  type onStart    = Option[Throwable] => Boolean

  /**
   * Registers a callback to be called when an event happens on the given fd.
   */
  def registerOnFd(fd: Int, cb: onFd, expectedEvents: EpollEvents): Unit

  /**
   * Changes the expected events for the given fd.
   */
  def expectFromFd(fd: Int, expectedEvents: EpollEvents): Unit

  /**
   * Removes the callback for the given fd.
   */
  def removeOnFd(fd: Int): Unit

  /**
   * Registers a callback to be called on each cycle.
   */
  def registerOnCycle(cb: onCycle): Unit

  /**
   * Registers a callback to be called on the start of the poller.
   */
  def registerOnStart(cb: onStart): Unit

  /**
   * Registers a callback to be called when the given deadline is reached.
   *
   * @return an unique id to remove the callback
   */
  def registerOnDeadline(deadLine: Long, cb: onDeadline): Long

  /**
   * Removes the callback for the given deadline.
   */
  def removeOnDeadline(id: Long): Unit
}

/**
 * A passive poller does not poll periodically.
 * The caller have to call `waitUntil` frequently to proceed the poller.
 */
trait PassivePoller extends Poller {

  /**
   * Waits until any event has happened or any deadline is reached.
   */
  def waitUntil(): Unit
}
