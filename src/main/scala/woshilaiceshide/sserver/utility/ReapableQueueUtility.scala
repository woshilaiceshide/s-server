package woshilaiceshide.sserver.utility;

import scala.annotation._

trait ReapableQueueUtility {

  @tailrec final def foreach[T](reaped: ReapableQueue.Reaped[T], runner: T => Unit): Unit = {
    val current = reaped.get_current_and_advance()
    if (current != null) {
      if (current.isNormal) {
        runner(current.value)
      }
      foreach(reaped, runner)
    }
  }

}

object ReapableQueueUtility extends ReapableQueueUtility