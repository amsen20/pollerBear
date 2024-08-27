package tests

import pollerBear.epoll.EpollInputEvents
import pollerBear.runtime._
import scala.collection.mutable._
import scala.scalanative.libc
import scala.scalanative.posix.unistd
import scala.scalanative.runtime._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

class Pipe(bufferSize: Int)(
    using Zone
) {
  val fds = alloc[Int](2) // [read, write]
  val buf = alloc[Byte](bufferSize)

  private def init(): Unit =
    if unistd.pipe(fds) != 0 then throw RuntimeException("failed to create pipe")

  init()

  def read(): String =
    val n = unistd.read(fds(0), buf, bufferSize.toCSize)
    if n < 0 then throw RuntimeException("failed to read from pipe")

    fromCString(buf)

  def write(msg: String): Unit =
    val cstr = toCString(msg)
    val n    = unistd.write(fds(1), cstr, libc.string.strlen(cstr))
    if n < 0 then throw RuntimeException("failed to write to pipe")
    if n != libc.string.strlen(cstr) then throw RuntimeException("failed to write all bytes")

}

class PipeScenarioSuite extends munit.FunSuite {

  test("pipe class test") {
    Zone:
      val pipe = Pipe(1024)

      pipe.write("hello")
      assertEquals(pipe.read(), "hello")
  }

  test("single pipe") {
    var onReadCleanedUp  = false
    var onCycleCleanedUp = false
    var onStartCalled    = false
    var expecting        = 0

    var aftersCreated = 0
    var aftersCalled  = 0

    def getAfter: Poller#AfterModification =
      aftersCreated += 1
      {
        case Some(e) =>
          this.fail(s"after called with an error: $e")
        case None =>
          aftersCalled += 1
      }

    Zone:
      val pipe = Pipe(1024)
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

      withPassivePoller { poller =>
        poller.registerOnFd(pipe.fds(0), onRead, EpollInputEvents().input(), getAfter)
        poller.registerOnStart(onStart, getAfter)
        poller.registerOnCycle(onCycle, getAfter)
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
    assertEquals(aftersCreated, aftersCalled)
  }
}
