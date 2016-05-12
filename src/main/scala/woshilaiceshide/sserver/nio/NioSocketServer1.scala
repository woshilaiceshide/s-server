package woshilaiceshide.sserver.nio

import java.net.InetSocketAddress
import java.util.Iterator

import java.io.Closeable
import java.io.IOException

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels._
import java.nio.charset._

import woshilaiceshide.sserver.utility.Utility

/**
 * a nio socket server in a single thread.
 */
class NioSocketServer1(interface: String,
  port: Int,
  channel_hander_factory: ChannelHandlerFactory,
  listening_channel_configurator: ServerSocketChannelWrapper => Unit = _ => {},
  accepted_channel_configurator: SocketChannelWrapper => Unit = _ => {},
  receive_buffer_size: Int = 1024,
  socket_max_idle_time_in_seconds: Int = 90,
  max_bytes_waiting_for_written_per_channel: Int = 64 * 1024,
  default_select_timeout: Int = 30 * 1000,
  enable_fuzzy_scheduler: Boolean = false) extends NioSocketReaderWriter(channel_hander_factory, receive_buffer_size, socket_max_idle_time_in_seconds,
  max_bytes_waiting_for_written_per_channel, default_select_timeout, enable_fuzzy_scheduler) {

  private val ssc = ServerSocketChannel.open()

  protected override def do_start(): Unit = {

    super.do_start()

    val wrapper = new ServerSocketChannelWrapper(ssc)
    listening_channel_configurator(wrapper)
    if (-1 == wrapper.backlog) {
      ssc.socket().bind(new InetSocketAddress(interface, port))
    } else {
      ssc.socket().bind(new InetSocketAddress(interface, port), wrapper.backlog)
    }

    ssc.configureBlocking(false)
    this.register(ssc, SelectionKey.OP_ACCEPT, null)

  }
  protected override def stop_roughly(): Unit = {
    safeClose(ssc)
    super.stop_roughly()

  }
  protected override def stop_gracefully(): Boolean = {
    safeClose(ssc)
    super.stop_gracefully()
  }
  protected override def has_remaining_work(): Boolean = {
    super.has_remaining_work()
  }
  protected override def before_next_loop(): Unit = {
    super.before_next_loop()
  }
  protected override def process_selected_key(key: SelectionKey): Unit = {
    if (key.isAcceptable()) {
      val ssc = key.channel().asInstanceOf[ServerSocketChannel]
      val channel = ssc.accept()
      val wrapper = new SocketChannelWrapper(channel)
      accepted_channel_configurator(wrapper)
      try {
        channel.configureBlocking(false)
        add_a_new_socket_channel(channel)
      } catch {
        case ex: Throwable => {
          SelectorRunner.warn(ex, "when a new channel is accepted.")
          safeClose(channel)
        }
      }
    } else {
      super.process_selected_key(key)
    }
  }

}