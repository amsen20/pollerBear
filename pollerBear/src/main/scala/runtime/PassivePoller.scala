package pollerBear
package runtime

import epoll._
import pollerBear.logger.PBLogger

/**
 * A passive poller does not poll periodically.
 * The caller have to call `waitUntil` frequently to proceed the poller.
 */
def withPassivePoller[T](body: PassivePoller => T): Unit =
  Epoll() { epoll =>
    val poller = new PollerImpl(epoll)
    try
      body(poller)
    finally
      poller.cleanUp()
  }
