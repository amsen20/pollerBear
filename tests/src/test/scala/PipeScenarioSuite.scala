package tests

import pollerBear.epoll.EpollInputEvents
import pollerBear.runtime._
import scala.collection.mutable._
import scala.scalanative.libc
import scala.scalanative.posix.unistd
import scala.scalanative.runtime._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._
import tests.tools.TestPipe

class PipeScenarioSuite extends munit.FunSuite {

  test("pipe class test") {
    Zone:
      val pipe = TestPipe(1024)

      pipe.write("hello")
      assertEquals(pipe.read(), "hello")
  }

  test("single pipe") {
    var onReadCleanedUp  = false
    var onCycleCleanedUp = false
    var onStartCalled    = false
    var expecting        = 0

    Zone:
      val pipe = TestPipe(1024)
      def onRead: Poller#OnFd = {
        case Left(events) =>
          val msg = pipe.read()
          assertEquals(msg.toInt, expecting)
          true
        case Right(e) =>
          onReadCleanedUp = true
          false
      }

      def onCycle: Poller#OnCycle = {
        case Some(e) =>
          onCycleCleanedUp = true
          false
        case None =>
          write()
          true
      }

      def onStart: Poller#OnStart = {
        case Some(e) =>
          println("onStart error: ")
          e.printStackTrace()
          throw new RuntimeException("onStart should not be called with an error")
        case None =>
          if !onStartCalled then onStartCalled = true
          else throw new RuntimeException("onStart should be called only once")

          write()
          false
      }

      def write(): Unit = {
        expecting += 1
        pipe.write(expecting.toString)
      }

      withPassivePoller(16) { poller =>
        poller.registerOnFd(pipe.fds(0), onRead, EpollInputEvents().input())
        poller.registerOnStart(onStart)
        poller.registerOnCycle(onCycle)
        for i <- 0 until 10 do
          try poller.waitUntil()
          catch
            case e: Throwable =>
              e.printStackTrace()
              throw e
      }

    assert(onReadCleanedUp)
    assert(onCycleCleanedUp)
    assert(onStartCalled)
    assertEquals(expecting, 11)
  }
}
