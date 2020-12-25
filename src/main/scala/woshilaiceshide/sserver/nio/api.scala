package woshilaiceshide.sserver.nio

;

import java.net.InetSocketAddress
import java.util.Iterator

import java.io.Closeable
import java.io.IOException

import java.nio.ByteBuffer
import java.nio.channels._

import scala.annotation.tailrec

//public 'api's here

final class ServerSocketChannelWrapper(channel: ServerSocketChannel) {
  def setOption[T](name: java.net.SocketOption[T], value: T) = {
    channel.setOption(name, value)
  }

  def validOps() = channel.validOps()

  def supportedOptions() = channel.supportedOptions()

  private[nio] var backlog: Int = -1

  def setBacklog(backlog: Int) = {
    this.backlog = backlog
  }
}

final class SocketChannelWrapper(private[nio] val channel: SocketChannel) {
  def setOption[T](name: java.net.SocketOption[T], value: T) = {
    channel.setOption(name, value)
  }

  def validOps() = channel.validOps()

  def supportedOptions() = channel.supportedOptions()
}

object ChannelClosedCause extends scala.Enumeration {
  val UNKNOWN = Value
  //the upper business codes closed the channel
  val BY_BIZ = Value
  val SERVER_STOPPING = Value
  val BY_PEER = Value
  val SOCKET_CLOSED_UNEXPECTED = Value
  val SOCKET_CLOSED_NORMALLY = Value
  val IDLED = Value
}

//it may be not thread safe
trait Cacheable {
  def write(bytes: ByteBuffer, bytes_is_reusable: Boolean): WriteResult

  def flush(): WriteResult
}

/*
   * all operations are thread safe
   */
trait ChannelWrapper {

  /**
   * if rightNow is false, then close gracefully.
   */
  def closeChannel(rightNow: Boolean = false, attachment: Option[_] = None): Unit

  def post_to_io_thread(task: Runnable): Boolean

  def remoteAddress: java.net.SocketAddress

  def localAddress: java.net.SocketAddress

  /**
   * Only the writing to the channel is taken into account when calculating the idle-time-out by default.
   * So if transferring big files, such as in http chunking requests that last long time, use resetIdle().
   */
  def resetIdle(): Unit

  override def toString() = s"""${remoteAddress}->${localAddress}@@${hashCode}}"""

  /**
   * @param bytes_is_reusable
   * this parameter is an advanced one. it should be false in general.
   * if bytes_is_reusable is true, then the given 'bytes' will be reused directly.
   * if it's false, s-server will use its own internal buffer to copy the memory for the following socket writes.
   *
   * 1.
   * if bytes that are already waiting for written is more than max_bytes_waiting_for_written_per_channel,
   * then no bytes will be written, except for write_even_if_too_busy is true.
   * buf after this method's execution, byte waiting for written may be more than max_bytes_waiting_for_written_per_channel.
   * if you are a lazy coder, then use true for write_even_if_too_busy.
   *
   * 2.
   * all bytes are written successfully, or none written(no partial written).
   */
  def write(bytes: Array[Byte], write_even_if_too_busy: Boolean, bytes_is_reusable: Boolean): WriteResult = {
    write(bytes, 0, bytes.length, write_even_if_too_busy, bytes_is_reusable)
  }

  def write(bytes: Array[Byte], offset: Int, length: Int, write_even_if_too_busy: Boolean, bytes_is_reusable: Boolean): WriteResult = {
    write(ByteBuffer.wrap(bytes, offset, length), write_even_if_too_busy, bytes_is_reusable)
  }

  def write(buffer: ByteBuffer, write_even_if_too_busy: Boolean): WriteResult = {
    write(buffer, write_even_if_too_busy, false)
  }

  def write(buffer: ByteBuffer, write_even_if_too_busy: Boolean, bytes_is_reusable: Boolean): WriteResult = {
    write(buffer, write_even_if_too_busy, bytes_is_reusable, true)
  }

  //TODO what's about a optional customized event followed by this writing?
  def write(buffer: ByteBuffer, write_even_if_too_busy: Boolean, bytes_is_reusable: Boolean, as_soon_as_possible: Boolean): WriteResult

  //the result Cacheable is not thread safe
  def cacheable(capacity: Int): Cacheable

  def is_open(): Boolean

}

/**
 * this trait is full of sinks:
 *
 * 1. Every sink receives a channel wrapper, so that business codes could leave the channel wrapper off.
 *
 * 2. all the sinks are executed in the same thread(or fed to the same custom executor.
 * so put your initialization codes in 'channelOpened(...)' instead of constructors.
 */
trait ChannelHandler {

  /**
   * put your initialization codes here, instead of constructors..
   */
  def channelOpened(channelWrapper: ChannelWrapper): Unit

  /**
   * input ended but output may be still open.
   * business codes may "ping" to find out weather the peer is fine, or just shutdown the whole socket in this situation.
   * note that half-duplex can not be detected reliably.
   * a good suggestion is that "not use this sink" and set 'woshilaiceshide.sserver.nio.XNioConfigurator.allow_half_closure' to false.
   */
  def inputEnded(channelWrapper: ChannelWrapper): Unit

  /**
   * byteBuffer can not be changed by the method body's point of view(aka, readonly in the its point of view),
   * but may be changed by the underlying socket server,
   * because byteBuffer's inner array is shared with the writable buffer used by the underlying socket server.
   * the returned handler will be used next time.
   */
  def bytesReceived(byteBuffer: ByteBuffer, channelWrapper: ChannelWrapper): ChannelHandler

  /**
   * if the handler does not rest the channel using 'resetIdle()', then the channel will closed roughly.
   */
  def channelIdled(channelWrapper: ChannelWrapper): Unit

  /**
   * the previous transport is paused because of too many bytes waiting for transport.
   * now please continue your transport.
   * this sink is important for throttling.
   */
  def channelWritable(channelWrapper: ChannelWrapper): Unit

  def channelClosed(channelWrapper: ChannelWrapper, cause: ChannelClosedCause.Value, attachment: Option[_]): Unit
}

/**
 * information about channels. DO NOT use me to execute operations on channels.
 */
trait ChannelInformation {
  def remoteAddress: java.net.SocketAddress

  def localAddress: java.net.SocketAddress
}

trait ChannelHandlerFactory {
  /**
   * if asynchronization is needed, put your asynchronization in the returned ChannelHandler.
   */
  def getHandler(channel: ChannelInformation): Option[ChannelHandler]

  def close() = {}
}

trait SelectorRunnerConfigurator {
  /**
   * Use a customized key set(based on array) instead of the builtin hash set if True.
   *
   * @see woshilaiceshide.sserver.nio.SelectedKeySet
   * @see woshilaiceshide.sserver.nio.SelectorRunner#new_selector()
   * @return
   */
  def try_to_optimize_selector_key_set: Boolean

  def default_select_timeout: Int

  def enable_fuzzy_scheduler: Boolean

  def io_thread_factory: java.util.concurrent.ThreadFactory
}

trait ByteBufferPool {
  def borrow_buffer(size_hint: Int): Borrowed

  /**
   * @param helper
   * used by the pool when previous buffers returned.
   * for example, you can use 'helper' to identify the internal bucket from which the returned buffer is borrowed, and lock conflicts may be reduced.
   */
  def return_buffer(buffer: ByteBuffer, helper: Byte): Unit

  def free(): Unit
}

import woshilaiceshide.sserver.utility.ArrayNodeStack

final case class Borrowed(helper: Byte, buffer: ByteBuffer)

/**
 * this pool will cache a fixed count of byte buffers in the thread locally, and every buffer is of size 'fragment_size'.
 */
final case class FixedByteBufferPool(fragment_size: Int, cached_count: Int, direct: Boolean) extends ByteBufferPool {

  private var pool = new ArrayNodeStack[ByteBuffer](cached_count)
  for (i <- 0 until cached_count) {
    val tmp = if (!direct) ByteBuffer.allocate(fragment_size) else ByteBuffer.allocateDirect(fragment_size)
    pool.push(tmp)
  }

  def borrow_buffer(size_hint: Int): Borrowed = {
    val tmp = pool.pop()
    if (tmp.isEmpty) {
      Borrowed(2, ByteBuffer.allocate(fragment_size * Math.min(size_hint / fragment_size + 1, 2)))
    } else {
      Borrowed(1, tmp.get)
    }
  }

  def return_buffer(buffer: ByteBuffer, helper: Byte): Unit = {
    if (1 == helper) {
      buffer.clear()
      pool.push(buffer)
    }
  }

  def free(): Unit = {
    pool.clear()
    pool = null
  }
}

final case class SynchronizedFragmentedByteBufferPool(fragment_size: Int, cached_count: Int, direct: Boolean) extends ByteBufferPool {

  private var pool = new ArrayNodeStack[ByteBuffer](cached_count)
  for (i <- 0 until cached_count) {
    val tmp = if (!direct) ByteBuffer.allocate(fragment_size) else ByteBuffer.allocateDirect(fragment_size)
    pool.push(tmp)
  }

  def borrow_buffer(size_hint: Int): Borrowed = this.synchronized {
    val tmp = pool.pop()
    if (tmp.isEmpty) {
      Borrowed(2, ByteBuffer.allocate(fragment_size * Math.min(size_hint / fragment_size + 1, 2)))
    } else {
      Borrowed(1, tmp.get)
    }
  }

  def return_buffer(buffer: ByteBuffer, helper: Byte): Unit = this.synchronized {
    if (1 == helper) {
      buffer.clear()
      pool.push(buffer)
    }
  }

  def free(): Unit = this.synchronized {
    pool.clear()
    pool = null
  }
}

trait ByteBufferPoolFactory {
  def get_pool_used_by_io_thread(): ByteBufferPool

  def get_pool_used_by_biz_thread(): ByteBufferPool

  def free(): Unit
}

final case class DefaultByteBufferPoolFactory(fragment_size: Int = 512, cached_count: Int = 64, direct: Boolean = true) extends ByteBufferPoolFactory {

  def get_pool_used_by_io_thread(): ByteBufferPool = {
    FixedByteBufferPool(fragment_size, cached_count, direct)
  }

  def get_pool_used_by_biz_thread(): ByteBufferPool = {
    SynchronizedFragmentedByteBufferPool(fragment_size, cached_count, direct)
  }

  def free(): Unit = {}

}

trait NioConfigurator extends SelectorRunnerConfigurator {

  /**
   * how many runners to read/write sockets?
   *
   * if it's set to ZERO, then only ONE runner exists, and it do all the things for listening, reading, writing.
   */
  def count_for_reader_writers: Int

  def listening_channel_configurator: ServerSocketChannelWrapper => Unit

  def accepted_channel_configurator: SocketChannelWrapper => Unit

  def receive_buffer_size: Int

  def socket_max_idle_time_in_seconds: Int

  def max_bytes_waiting_for_written_per_channel: Int

  def check_idle_interval_in_seconds: Int

  /**
   * set 'sun.nio.ch.bugLevel' to a blank string if it does not exist in the 'System.getProperties()'
   */
  def revise_sun_jdk_bug_level: Boolean

  /**
   * @see 'woshilaiceshide.sserver.nio.ChannelHandler.inputEnded(channelWrapper: ChannelWrapper)'
   */
  def allow_half_closure: Boolean

  /**
   * this pool is only used by 'SelectorRunner' for the bytes those will be written to the socket internally.
   *
   * you can use 'woshilaiceshide.sserver.nio.FixedByteBufferPool' with your own parameters.
   */
  def buffer_pool_factory: ByteBufferPoolFactory

  def spin_count_when_write_immediately: Int
}

final case class XNioConfigurator(
                                   rebuild_selector_threshold: Int = 1,
                                   try_to_optimize_selector_key_set: Boolean = true,
                                   default_select_timeout: Int = 30 * 1000,
                                   enable_fuzzy_scheduler: Boolean = false,
                                   io_thread_factory: java.util.concurrent.ThreadFactory = new java.util.concurrent.ThreadFactory() {
                                     def newThread(r: Runnable) = {
                                       new Thread(r)
                                     }
                                   },

                                   /**
                                    * if count_for_reader_writers is 0, then read/write will be in the same thread as the acceptor,
                                    * no extra threads will be created.
                                    */
                                   count_for_reader_writers: Int,
                                   listening_channel_configurator: ServerSocketChannelWrapper => Unit = _ => {},
                                   accepted_channel_configurator: SocketChannelWrapper => Unit = _ => {},
                                   receive_buffer_size: Int = 1024 * 4,
                                   socket_max_idle_time_in_seconds: Int = 90,
                                   max_bytes_waiting_for_written_per_channel: Int = 64 * 1024,
                                   check_idle_interval_in_seconds: Int = 60,
                                   revise_sun_jdk_bug_level: Boolean = true,

                                   /**
                                    * @see woshilaiceshide.sserver.nio.ChannelHandler.inputEnded(channelWrapper: ChannelWrapper).
                                    *
                                    * a good suggestion is keep it 'false'
                                    */
                                   allow_half_closure: Boolean = false,
                                   buffer_pool_factory: ByteBufferPoolFactory = DefaultByteBufferPoolFactory(),
                                   spin_count_when_write_immediately: Int = 1) extends NioConfigurator

object NioSocketServer {

  def apply(
             interface: String,
             port: Int,
             channel_handler_factory: ChannelHandlerFactory,
             configurator: NioConfigurator) = {

    if (configurator.revise_sun_jdk_bug_level) {
      val key = "sun.nio.ch.bugLevel"
      val value = System.getProperty(key, null)
      if (value == null) {
        System.setProperty(key, "")
      }
    }

    if (configurator.count_for_reader_writers == 0) {
      new NioSocketServer1(interface, port, channel_handler_factory, configurator)

    } else {
      new NioSocketAcceptor(interface, port, channel_handler_factory, configurator)

    }
  }

}

//}