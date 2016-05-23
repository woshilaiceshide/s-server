package woshilaiceshide.sserver.nio;

import java.net.InetSocketAddress
import java.util.Iterator

import java.io.Closeable
import java.io.IOException

import java.nio.ByteBuffer
import java.nio.channels._

import scala.annotation.tailrec

/**
 * public 'api's here
 */
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

final class SocketChannelWrapper(channel: SocketChannel) {
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
  val BECUASE_SOCKET_CLOSED_UNEXPECTED = Value
  val BECUASE_SOCKET_CLOSED_NORMALLY = Value
  val BECAUSE_IDLE = Value
}

object WriteResult extends scala.Enumeration {
  val WR_OK = Value

  //commented. this value makes no sense in practice.
  //written succeeded, but the inner buffer pool is full(overflowed in fact), which will make the next writing failed definitely.
  //wait for 'def channelWritable(...)' if this value encountered.
  //val WR_OK_BUT_OVERFLOWED = Value

  val WR_FAILED_BECAUSE_TOO_MANY_WRITES_EXISTED = Value
  val WR_FAILED_BECAUSE_CHANNEL_CLOSED = Value

  //commented. this value makes no sense in practice, but make things more complicated.
  //val WR_FAILED_BECAUSE_EMPTY_CONTENT_TO_BE_WRITTEN = Value
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
   *
   * 1.
   *   if bytes that are already waiting for written is more than max_bytes_waiting_for_written_per_channel,
   *   then no bytes will be written, except for write_even_if_too_busy is true.
   *   buf after this method's execution, byte waiting for written may be more than max_bytes_waiting_for_written_per_channel.
   *   if you are a lazy coder, then use true for write_even_if_too_busy.
   *
   * 2.
   *   all bytes are written successfully, or none written(no partial written).
   *
   * 3.
   *   if generate_writing_event is true, then 'writtenHappend' will be fired.
   *   note that 'writtenHappened' means just an "writing' event, and zero byte may be written.
   *   multiple 'generate_writing_event' may be folded into one.
   */
  def write(bytes: Array[Byte], write_even_if_too_busy: Boolean, generate_writing_event: Boolean): WriteResult.Value = {
    write(bytes, 0, bytes.length, write_even_if_too_busy, generate_writing_event)
  }

  def write(bytes: Array[Byte], offset: Int, length: Int, write_even_if_too_busy: Boolean, generate_writing_event: Boolean): WriteResult.Value
}

/**
 * this trait is full of sinks. Every sink receives a channel wrapper,
 * so that business codes could leave the channel wrapper off.
 */
trait ChannelHandler {

  def channelOpened(channelWrapper: ChannelWrapper): Unit
  /**
   * input ended but output may be still open.
   * business codes may "ping" to find out weather the peer is fine, or just shutdown the whole socket in this situation.
   * note that half-duplex can not be detected reliably.
   * a good suggestion is that "not use this sink" and set 'woshilaiceshide.sserver.nio.XNioConfigurator.allow_hafl_closure' to false.
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

  /**
   * this sink may be used when some continuation should start after some writes happened.
   * see 'ChannelWrapper.write(...)' for more information.
   */
  def writtenHappened(channelWrapper: ChannelWrapper): ChannelHandler

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

abstract case class X(i: Int)

trait SelectorRunnerConfigurator {
  def rebuild_selector_for_epoll_100_perent_cpu_bug: Boolean
  def rebuild_selector_threshold: Int
  def try_to_optimize_selector_key_set: Boolean
  def default_select_timeout: Int
  def enable_fuzzy_scheduler: Boolean
}

trait NioConfigurator extends SelectorRunnerConfigurator {
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
   * see woshilaiceshide.sserver.nio.ChannelHandler.inputEnded(channelWrapper: ChannelWrapper)
   */
  def allow_hafl_closure: Boolean
}

final case class XNioConfigurator(

  rebuild_selector_for_epoll_100_perent_cpu_bug: Boolean = false,
  rebuild_selector_threshold: Int = 1,
  try_to_optimize_selector_key_set: Boolean = true,
  default_select_timeout: Int = 30 * 1000,
  enable_fuzzy_scheduler: Boolean = false,

  /**
   * if count_for_reader_writers is 0, then read/write will be in the same thread as the acceptor,
   * no extra threads will be created.
   */
  count_for_reader_writers: Int,
  listening_channel_configurator: ServerSocketChannelWrapper => Unit = _ => {},
  accepted_channel_configurator: SocketChannelWrapper => Unit = _ => {},
  receive_buffer_size: Int = 1024,
  socket_max_idle_time_in_seconds: Int = 90,
  max_bytes_waiting_for_written_per_channel: Int = 64 * 1024,
  check_idle_interval_in_seconds: Int = 60,
  revise_sun_jdk_bug_level: Boolean = true,
  /**
   * see woshilaiceshide.sserver.nio.ChannelHandler.inputEnded(channelWrapper: ChannelWrapper).
   *
   * a good suggestion is keep it 'false'
   */
  allow_hafl_closure: Boolean = false) extends NioConfigurator

object NioSocketServer {

  def apply(
    interface: String,
    port: Int,
    channel_hander_factory: ChannelHandlerFactory,
    configurator: NioConfigurator) = {

    if (configurator.revise_sun_jdk_bug_level) {
      val key = "sun.nio.ch.bugLevel"
      val value = System.getProperty(key, null)
      if (value == null) {
        System.setProperty(key, "")
      }
    }

    if (configurator.count_for_reader_writers == 0) {
      new NioSocketServer1(interface, port, channel_hander_factory, configurator)

    } else {
      new NioSocketAcceptor(interface, port, channel_hander_factory, configurator)

    }
  }

}

//}