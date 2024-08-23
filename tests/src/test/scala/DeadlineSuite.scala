package tests

import pollerBear.epoll.EpollInputEvents
import pollerBear.runtime._
import scala.collection.mutable
import scala.collection.mutable._
import scala.collection.View.Single
import scala.scalanative.libc
import scala.scalanative.posix.poll
import scala.scalanative.posix.unistd
import scala.scalanative.runtime._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

class DeadlineSuite extends munit.FunSuite {
  test("couple of deadlines") {
    withPassivePoller { poller =>
      val start = System.currentTimeMillis()
      val deadlines = List(
        start + 100L,
        start + 200L,
        start + 300L,
        start + 400L,
        start + 500L
      )
      val called: mutable.ArraySeq[Boolean] = (for _ <- deadlines yield false).toArray
      def onDeadline(i: Int): Poller#onDeadline = {
        case None =>
          called(i) = true
          false
        case Some(e: PollerCleanUpException) => false
        case Some(e) =>
          fail(s"unexpected error: $e")
          false
      }

      val IDs =
        for i <- 0 until deadlines.length
        yield poller.registerOnDeadline(deadlines(i), onDeadline(i))

      poller.waitUntil()
      val first = System.currentTimeMillis()
      poller.removeOnDeadline(IDs(1))
      poller.waitUntil()
      val second = System.currentTimeMillis()
      poller.removeOnDeadline(IDs(3))
      poller.waitUntil()
      val third = System.currentTimeMillis()

      val diff = (first - deadlines(0)) + (second - deadlines(2)) + (third - deadlines(4))
      assert(math.abs(diff) < 10)
    }
  }
}
