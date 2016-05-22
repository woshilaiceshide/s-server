package woshilaiceshide.sserver.nio

import java.nio._
import java.util.concurrent.ThreadFactory

object MultipleThreadHandlerFactory {

  private val STOP = new Runnable() { def run() = {} }

  class Worker(capacity: Int = Integer.MAX_VALUE) extends Runnable {

    private val queue = new java.util.concurrent.LinkedBlockingQueue[Runnable](capacity)
    //Integer.MAX_VALUE should be enough.
    private val should_be_closed = new java.util.concurrent.LinkedBlockingQueue[(MultipleThreadHandler, ChannelWrapper)](Integer.MAX_VALUE)

    def add(task: Runnable) = queue.offer(task)

    def close_this_handler(handler: MultipleThreadHandler, channelWrapper: ChannelWrapper) = {
      should_be_closed.put((handler, channelWrapper))
    }

    def end() = queue.put(STOP)

    @scala.annotation.tailrec
    private def close_all(): Unit = {
      val one = should_be_closed.poll()
      if (null != one) {
        one._1.finished = true
        one._1.channelClosed(one._2, ChannelClosedCause.UNKNOWN, None)
        close_all()
      }
    }

    @scala.annotation.tailrec
    private def process(): Unit = {
      val task = queue.take()
      if (task ne STOP) {
        task.run()
        close_all()
        process()
      }
    }

    def run(): Unit = process()

  }
}

import MultipleThreadHandlerFactory._

class MultipleThreadHandlerFactory(nThreads: Int, threadFactory: ThreadFactory, capacityForEveryWorker: Int, factory: ChannelHandlerFactory) extends ChannelHandlerFactory {

  private val workers = Array.fill(nThreads)(new Worker(capacityForEveryWorker))
  private val threads = workers.map { worker => threadFactory.newThread(worker) }
  threads.map { _.start() }

  def getHandler(channel: ChannelInformation): Option[ChannelHandler] = {

    factory.getHandler(channel) match {
      case None => None
      case Some(handler) => {
        val partition = Math.abs(channel.hashCode() % nThreads)
        val worker = workers(partition)
        Some(new MultipleThreadHandler(handler, worker))
      }
    }
  }

  //TODO
  private def safeClose(x: ChannelHandlerFactory) = try { x.close(); } catch { case ex: Throwable => { ex.printStackTrace() } }
  private def safeOp[T](x: => T) = try { x; } catch { case ex: Throwable => { ex.printStackTrace() } }

  override def close() = {
    safeClose(factory)
    workers.map { worker => safeOp { worker.end() } }
    threads.map { thread => safeOp { thread.interrupt() } }
  }

}

class MultipleThreadHandler(var handler: ChannelHandler, worker: Worker) extends ChannelHandler {

  private[nio] var finished = false

  private def tryAdd(task: Runnable, channelWrapper: ChannelWrapper) = {
    if (!worker.add(task)) {
      //too busy
      channelWrapper.closeChannel(true)
    }
  }

  //this sink is important, which will help you to run initlization codes in the same thread as other sinks.
  def channelOpened(channelWrapper: ChannelWrapper): Unit = {
    tryAdd(new Runnable() {
      def run() = {
        if (handler != null && !finished) {
          handler.channelOpened(channelWrapper)
        }
      }
    }, channelWrapper)
  }

  def inputEnded(channelWrapper: ChannelWrapper): Unit = {
    tryAdd(new Runnable() {
      def run() = {
        if (handler != null && !finished) {
          handler.inputEnded(channelWrapper)
        }
      }
    }, channelWrapper)
  }

  @inline private def deepCopy(o: ByteBuffer) = {

    val c = if (o.isDirect()) {
      ByteBuffer.allocateDirect(o.remaining())
    } else {
      ByteBuffer.allocate(o.remaining())
    }

    val x = o.asReadOnlyBuffer()
    c.put(x)
    c.flip()
    c
  }

  def bytesReceived(byteBuffer: ByteBuffer, channelWrapper: ChannelWrapper): ChannelHandler = {

    val clone = deepCopy(byteBuffer)

    tryAdd(new Runnable() {
      def run() = {
        if (handler != null && !finished) {
          val newHandler = handler.bytesReceived(byteBuffer, channelWrapper)
          handler = newHandler
          if (handler == null) {
            channelWrapper.closeChannel(false)
          }
        }
      }
    }, channelWrapper)

    this
  }

  def channelIdled(channelWrapper: ChannelWrapper): Unit = {
    tryAdd(new Runnable() {
      def run() = {
        if (handler != null && !finished) {
          handler.channelIdled(channelWrapper)
        }
      }
    }, channelWrapper)
  }

  def channelWritable(channelWrapper: ChannelWrapper): Unit = {
    tryAdd(new Runnable() {
      def run() = {
        if (handler != null && !finished) {
          handler.channelWritable(channelWrapper)
        }
      }
    }, channelWrapper)
  }

  def writtenHappened(channelWrapper: ChannelWrapper): ChannelHandler = {
    tryAdd(new Runnable() {
      def run() = {
        if (handler != null && !finished) {
          val newHandler = handler.writtenHappened(channelWrapper)
          handler = newHandler
          if (handler == null) {
            channelWrapper.closeChannel(false)
          }
        }
      }
    }, channelWrapper)

    this
  }

  def channelClosed(channelWrapper: ChannelWrapper, cause: ChannelClosedCause.Value, attachment: Option[_]): Unit = {
    val task = new Runnable() {
      def run() = {
        if (handler != null && !finished) {
          handler.channelClosed(channelWrapper, cause, attachment)
        }
      }
    }
    if (!worker.add(task)) {
      worker.close_this_handler(this, channelWrapper)
    }
  }

}