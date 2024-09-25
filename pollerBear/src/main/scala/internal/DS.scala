package pollerBear
package internal

import java.util.concurrent.Semaphore
import scala.collection.mutable

/**
 * A thread safe data structure for storing deadlines and their associated callbacks.
 */
class Deadlines[OnDeadline] {
  type Deadline = (Long, Long) // (time, unique id)
  @volatile private var maxId = 0L
  private val deadLines       = mutable.Set[Deadline]()
  private val IdToDeadline    = mutable.HashMap[Long, Deadline]()   // id -> deadline
  private val onDeadlines     = mutable.HashMap[Long, OnDeadline]() // id -> callback

  def getMinDeadline(): Option[(Deadline, OnDeadline)] =
    synchronized:
      if (deadLines.isEmpty) None
      else {
        val (minDeadline, id) = deadLines.min
        Some(((minDeadline, id), onDeadlines(id)))
      }

  def addDeadline(deadlineTime: Long, onDeadline: OnDeadline): Long =
    synchronized:
      val id = maxId
      maxId += 1

      val deadline: Deadline = (deadlineTime, id)

      deadLines.add(deadline)
      IdToDeadline.put(id, deadline)
      onDeadlines.put(id, onDeadline)

      id

  def removeDeadline(id: Long): Option[(Deadline, OnDeadline)] =
    synchronized:
      IdToDeadline.get(id) match
        case Some(deadline) =>
          deadLines.remove(deadline)
          IdToDeadline.remove(id)
          val onDeadline = onDeadlines.remove(id).get

          Some((deadline, onDeadline))
        case None => None

  def foreachCallback(f: OnDeadline => Unit): Unit =
    synchronized:
      onDeadlines.values.foreach(f)

  def clear(): Unit =
    synchronized:
      deadLines.clear()
      IdToDeadline.clear()
      onDeadlines.clear()

}

/**
 * A thread-safe queue that allows pushing while popping.
 * But the pushes won't be seen to the popper until they release are finished with popping.
 */
class PushWhilePoppingQueue[T] {
  private val queue = mutable.Queue[T]()

  // It grantees that when the buffer is not empty, there is another thread popping.
  // And after popping the thread will empty the buffer. No, elements will be left in the buffer.
  private val bufferQueue = mutable.Queue[T]()

  // the semaphore acts as a lock (found no simple lock to implement Lock trait)
  private val readingLock = new Semaphore(1)

  @volatile private var threadIdPopping = -1L

  private def tryPush(elem: T): Boolean =
    if (readingLock.tryAcquire())
      queue.enqueue(elem)
      readingLock.release()
      true
    else false

  def push(elem: T): Unit =
    if !tryPush(elem) then
      bufferQueue.synchronized:
        if !tryPush(elem) then bufferQueue.enqueue(elem)

  /**
   * The method should be called only by the thread that is in the popping block.
   * In other cases, it will throw an IllegalStateException.
   */
  def pop(): Option[T] =
    if threadIdPopping != Thread.currentThread().threadId() then
      throw new IllegalStateException("Only the thread that is in popping block can pop")

    if queue.isEmpty then None
    else Some(queue.dequeue())

  def doPopping(body: => Unit): Unit =
    readingLock.acquireUninterruptibly()
    threadIdPopping = Thread.currentThread().threadId()

    try body
    finally
      bufferQueue.synchronized:
        while bufferQueue.nonEmpty do queue.enqueue(bufferQueue.dequeue())

      threadIdPopping = -1
      readingLock.release()

  def filterInPlace(f: T => Boolean): Unit =
    doPopping:
      queue.filterInPlace(f)

  def foreach(f: T => Unit): Unit =
    doPopping:
      queue.foreach(f)

  def clear(): Unit =
    doPopping:
      queue.clear()

}
