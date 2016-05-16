package woshilaiceshide.sserver.utility;

import scala.annotation._

trait ReapableQueueUtility {

  @tailrec final def foreach[T](queue: ReapableQueue.Reaped[T], runner: T => Unit): Unit = {
    val current = queue.get_current_and_advance()
    if (current != null) {
      runner(current.value)
      foreach(queue, runner)
    }
  }

}

object ReapableQueueUtility extends ReapableQueueUtility