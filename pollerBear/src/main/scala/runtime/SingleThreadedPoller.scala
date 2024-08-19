package pollerBear
package runtime

import epoll._

object SingleThreadedPoller {

  /**
   * Creates a new single threaded poller and runs the given body.
   * The caller have to call `waitUntil` frequently to proceed the poller.
   */
  def apply[T](body: Poller => T): Unit =
    Epoll() { epoll =>
      val poller = new PollerImpl(epoll)
      try
        body(poller)
      finally
        poller.cleanUp()
    }

}
