package pollerBear
package internal

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

  def applyCallbacks(f: OnDeadline => Unit): Unit =
    synchronized:
      onDeadlines.values.foreach(f)

  def clear(): Unit =
    synchronized:
      deadLines.clear()
      IdToDeadline.clear()
      onDeadlines.clear()

}
