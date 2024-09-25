package tests

import pollerBear.epoll.EpollEvents
import pollerBear.epoll.EpollInputEvents
import pollerBear.runtime._
import scala.collection.mutable._
import scala.scalanative.libc
import scala.scalanative.posix.unistd
import scala.scalanative.runtime._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._
import tests.tools.TestPipe

class MixedScenarioSuite extends munit.FunSuite {
  test("time sort") {
    println("starting time sort")
    val numList    = List(600, 300, 500, 100, 700, 800, 400, 200)
    val sortedList = ListBuffer[Int]()

    Zone:
      val pipe = TestPipe(1024)

      withPassivePoller(16) { poller =>
        numList.foreach(timeout =>
          val deadline = System.currentTimeMillis() + timeout
          poller.registerOnDeadline(
            deadline,
            _ =>
              poller.runAction(_ => pipe.write(timeout.toString))
              true
          )
        )

        poller.registerOnFd(
          pipe.fds(0),
          {
            case Left(_) =>
              val msg = pipe.read()
              sortedList.append(msg.toInt)
              true
            case Right(_) => false
          },
          EpollEvents().input()
        )

        while sortedList.size < numList.size do poller.waitUntil()
      }

    assertEquals(sortedList.toList, numList.sorted)
  }
}
