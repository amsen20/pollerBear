package tests

import pollerBear.epoll.EpollEvents
import pollerBear.epoll.EpollInputEvents
import pollerBear.runtime._
import scala.collection.mutable._
import scala.scalanative.libc
import scala.scalanative.libc.time
import scala.scalanative.posix.unistd
import scala.scalanative.runtime._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._
import tests.tools.TestPipe

class MultiThreadedSuite extends munit.FunSuite {

  val zone                = Zone.open()
  @volatile var condition = true

  val pingPipe = TestPipe(1024)(
    using zone
  )

  val pongPipe = TestPipe(1024)(
    using zone
  )

  @volatile var pingCount = 0
  @volatile var pongCount = 0

  def sendPing(poller: Poller) =
    poller.runAction(_ =>
      // println("> sending ping")
      pingPipe.write("ping")
      // println("> sent ping")
      pingCount += 1
    )

  def getPing(poller: Poller) =
    poller.runAction(_ =>
      // println("< getting ping")
      assert(pingPipe.read() == "ping")
      // println("< got ping")
    )

  def sendPong(poller: Poller) =
    poller.runAction(_ =>
      // println("> sending pong")
      pongPipe.write("pong")
      // println("> sent pong")
      pongCount += 1
    )

  def getPong(poller: Poller) =
    poller.runAction(_ =>
      // println("< getting pong")
      assert(pongPipe.read() == "pong")
      // println("< got pong")
    )

  def pingPong(): Unit =
    withPassivePoller(16) { poller =>
      poller.registerOnFd(
        pingPipe.fds(0),
        {
          case Left(_) =>
            getPing(poller)
            sendPong(poller)
            true
          case Right(_) => false
        },
        EpollEvents().input()
      )

      poller.registerOnCycle {
        case None =>
          poller.registerOnDeadline(
            System.currentTimeMillis() + 10,
            _ => false
          )
          true
        case Some(e) =>
          false
      }
      poller.wakeUp()

      while condition do poller.waitUntil()
    }

  class MyTimeOutException extends Exception

  test("ping pong") {
    val TIMEOUT = 10000
    try
      withActivePoller(16) {
        val poller = summon[ActivePoller]
        // Throws and exception for finishing the test scenario.
        // Also tests the scenario for exception propagation.
        poller.registerOnDeadline(
          System.currentTimeMillis() + TIMEOUT,
          {
            case None =>
              condition = false
              println("throwing exception")
              throw new MyTimeOutException()
            case Some(_) => false
          }
        )
        poller.wakeUp()

        poller.registerOnFd(
          pongPipe.fds(0),
          {
            case Left(_) =>
              getPong(poller)
              sendPing(poller)
              true
            case Right(_) => false
          },
          EpollEvents().input()
        )
        poller.wakeUp()

        // Start pinging.
        sendPong(poller)

        val pingPongThread = new Thread(() => pingPong())
        pingPongThread.start()
        pingPongThread.join()
      }
    catch
      case e: MyTimeOutException =>
        println("timeout")
    finally zone.close()

    assert(math.abs(pingCount - pongCount) <= 1)
    assert(pingCount > TIMEOUT / 5) // at least a ping every 5ms
    println(pingCount + pongCount)
  }
}
