/* Taken from https://github.com/armanbilge/epollcat */

/*
 * Copyright 2022 Arman Bilge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package pollerBear
package epoll

import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

@extern
private[epoll] object epoll {

  final val EPOLL_CTL_ADD = 1
  final val EPOLL_CTL_DEL = 2
  final val EPOLL_CTL_MOD = 3

  final val EPOLLIN = 0x001L
  final val EPOLLPRI = 0x002L
  final val EPOLLOUT = 0x004L
  final val EPOLLERR = 0x008L
  final val EPOLLHUP = 0x010L
  final val EPOLLRDHUP = 0x2000L

  final val EPOLLEXCLUSIVE = 1L << 28
  final val EPOLLWAKEUP = 1L << 29
  final val EPOLLONESHOT = 1L << 30
  final val EPOLLET = 1L << 31

  type epoll_event
  type epoll_data_t = Ptr[Byte]

  def epoll_create1(flags: Int): Int = extern

  def epoll_ctl(epfd: Int, op: Int, fd: Int, event: Ptr[epoll_event]): Int =
    extern

  def epoll_wait(
      epfd: Int,
      events: Ptr[epoll_event],
      maxevents: Int,
      timeout: Int,
  ): Int =
    extern

}

private[epoll] object epollImplicits {

  import epoll._

  implicit final class epoll_eventOps(epoll_event: Ptr[epoll_event]) {
    def events: CUnsignedInt = !epoll_event.asInstanceOf[Ptr[CUnsignedInt]]
    def events_=(events: CUnsignedInt): Unit =
      !epoll_event.asInstanceOf[Ptr[CUnsignedInt]] = events

    def data: epoll_data_t = {
      val offset =
        if (LinktimeInfo.target.arch == "x86_64")
          sizeof[CUnsignedInt]
        else
          sizeof[Ptr[Byte]]
      !(epoll_event.asInstanceOf[Ptr[Byte]] + offset)
        .asInstanceOf[Ptr[epoll_data_t]]
    }

    def data_=(data: epoll_data_t): Unit = {
      val offset =
        if (LinktimeInfo.target.arch == "x86_64")
          sizeof[CUnsignedInt]
        else
          sizeof[Ptr[Byte]]
      !(epoll_event.asInstanceOf[Ptr[Byte]] + offset)
        .asInstanceOf[Ptr[epoll_data_t]] = data
    }

  }

  implicit val epoll_eventTag: Tag[epoll_event] =
    if (LinktimeInfo.target.arch == "x86_64")
      Tag
        .materializeCArrayTag[Byte, Nat.Digit2[Nat._1, Nat._2]]
        .asInstanceOf[Tag[epoll_event]]
    else
      Tag
        .materializeCArrayTag[Byte, Nat.Digit2[Nat._1, Nat._6]]
        .asInstanceOf[Tag[epoll_event]]

}
