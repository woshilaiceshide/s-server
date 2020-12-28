package woshilaiceshide.sserver.utility

;

import scala.annotation._

trait ReapableQueueUtility {

  /**
   * TODO check the running thread is changed.
   * @param queue
   * @param reaper
   * @tparam T
   * @return reaped count
   */
  final def reap[T](queue: ReapableQueue[T], reaper: T => Unit): Int = {
    val reaped = queue.reap()
    if (null != reaped) {
      foreach(reaped, reaper, 0)
    } else {
      0
    }
  }

  @tailrec private final def foreach[T](reaped: ReapableQueue.Reaped[T], reaper: T => Unit, reapedCount: Int): Int = {
    val current = reaped.get_current_and_advance()
    if (current != null) {
      if (current.isNormal) {
        reaper(current.value)
        foreach(reaped, reaper, reapedCount + 1)
      } else {
        foreach(reaped, reaper, reapedCount)
      }
    } else {
      reapedCount
    }
  }

}

object ReapableQueueUtility extends ReapableQueueUtility