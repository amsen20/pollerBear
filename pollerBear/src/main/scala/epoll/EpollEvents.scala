package pollerBear
package epoll

import scala.scalanative.unsigned._

// TODO: document it

class EpollEvents {
  var isInput = false
  var isOutput = false
  var isError = false
  var isPri = false
  var isReadingHangUp = false
  var isHangUp = false

  def input(): EpollEvents =
    isInput = true
    this

  def output(): EpollEvents =
    isOutput = true
    this

  def error(): EpollEvents =
    isError = true
    this

  def readingHangUp(): EpollEvents =
    isReadingHangUp = true
    this

  def hangUp(): EpollEvents =
    isHangUp = true
    this

  def pri(): EpollEvents =
    isPri = true
    this

  def getMask(): UInt =
    var mask = 0L
    if isInput then mask |= epoll.EPOLLIN
    if isOutput then mask |= epoll.EPOLLOUT
    if isError then mask |= epoll.EPOLLERR
    if isPri then mask |= epoll.EPOLLPRI
    if isReadingHangUp then mask |= epoll.EPOLLHUP
    if isHangUp then mask |= epoll.EPOLLRDHUP
    mask.toUInt

}

object EpollEvents:
  def fromMask(mask: Int): EpollEvents =
    val ret = EpollEvents()
    if (mask & epoll.EPOLLIN) > 0 then ret.isInput = true
    if (mask & epoll.EPOLLOUT) > 0 then ret.isOutput = true
    if (mask & epoll.EPOLLERR) > 0 then ret.isError = true
    if (mask & epoll.EPOLLPRI) > 0 then ret.isPri = true
    if (mask & epoll.EPOLLHUP) > 0 then ret.isReadingHangUp = true
    if (mask & epoll.EPOLLRDHUP) > 0 then ret.isHangUp = true

    ret

class EpollInputEvents extends EpollEvents:
  var isEdgeTriggered = false
  var isOneShot = false
  var isWakeUp = false
  var isExclusive = false

  def edgeTriggered(): EpollInputEvents =
    isEdgeTriggered = true
    this

  def oneShot(): EpollInputEvents =
    isOneShot = true
    this

  def wakeUp(): EpollInputEvents =
    isWakeUp = true
    this

  def exclusive(): EpollInputEvents =
    isExclusive = true
    this

  override def getMask(): UInt =
    var mask = super.getMask().toLong
    if isEdgeTriggered then mask |= epoll.EPOLLET
    if isOneShot then mask |= epoll.EPOLLONESHOT
    if isWakeUp then mask |= epoll.EPOLLWAKEUP
    if isExclusive then mask |= epoll.EPOLLEXCLUSIVE

    mask.toUInt
