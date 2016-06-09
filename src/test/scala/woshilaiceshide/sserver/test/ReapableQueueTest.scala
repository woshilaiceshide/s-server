package woshilaiceshide.sserver.test

import woshilaiceshide.sserver.utility._

object ReapableQueueTest extends App {

  final case class Ball(index: Int, value: Long)

  final class Writer(index: Int, queue: ReapableQueue[Ball]) extends Runnable {

    var i: Long = 0L
    val r = new java.util.Random()

    var last: Long = -1

    @scala.annotation.tailrec
    def work(): Unit = {

      i = i + 1
      val tmp = i
      if (0 == i % 1000000) {
        val s = r.nextInt(10)
        Thread.sleep(s)
      }

      if (queue.add(Ball(index, tmp))) {
        work()
      } else {
        last = tmp
        println(s"writer-${index} ended at ${tmp}")
      }

    }

    override def run() = work()

  }

  final class Reader(ids: Array[Long], max_loop: Long, queue: ReapableQueue[Ball]) extends Runnable {

    var i: Long = 0L
    val r = new java.util.Random()

    @scala.annotation.tailrec
    def work(): Unit = {

      val tmp = i
      i = i + 1
      if (0 == i % 180000) {
        val s = r.nextInt(10)
        Thread.sleep(s)
      }

      val is_last_reap = i == max_loop

      if (is_last_reap) {
        queue.end()
      }

      val reaped = queue.reap(is_last_reap)
      if (null != reaped) {
        ReapableQueueUtility.foreach(reaped, (ball: Ball) => {
          val prev = ids(ball.index)
          if (prev + 1 != ball.value) {
            throw new Error("supposed to be not here!")
          } else {
            ids(ball.index) = ball.value
          }
        })
      }

      if (!is_last_reap) {
        work()
      } else {
        (0 until ids.length).map { x =>
          println(s"reap writer-${x} at ${ids(x)}")
        }
      }

    }

    override def run() = work()
  }

  def test(writer_count: Int, max_loop: Long) = {

    val queue = new ReapableQueue[Ball]()

    val ids = new Array[Long](writer_count)

    val reader = new Thread(new Reader(ids, max_loop, queue))
    val writer_loads = for (i <- 0 until writer_count) yield {
      new Writer(i, queue)
    }

    val writers = for (i <- 0 until writer_count) yield {
      new Thread(writer_loads(i))
    }

    writers.map { _.setDaemon(true) }
    reader.setDaemon(true)

    writers.map { _.start() }
    reader.start()

    writers.map { _.join() }
    reader.join()

    for (i <- 0 until ids.length) {
      if (ids(i) + 1 != writer_loads(i).last) {
        throw new Error("!!!")
      }
    }
  }

  for (i <- 0 until 300) {

    println(s"test ${i}")

    val writer_count = 2
    val max_loop = 20000000
    test(writer_count, max_loop)

    println("   ")
  }

}