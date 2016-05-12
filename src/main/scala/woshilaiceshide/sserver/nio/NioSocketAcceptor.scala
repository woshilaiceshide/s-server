package woshilaiceshide.sserver.nio

import java.net.InetSocketAddress
import java.util.Iterator

import java.io.Closeable
import java.io.IOException

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels._
import java.nio.charset._

import scala.annotation.tailrec

class NioSocketAcceptor(interface: String,
    port: Int,
    count_for_reader_writers: Int,
    channel_hander_factory: ChannelHandlerFactory,
    listening_channel_configurator: ServerSocketChannelWrapper => Unit = _ => {},
    accepted_channel_configurator: SocketChannelWrapper => Unit = _ => {},
    receive_buffer_size: Int = 1024,
    socket_max_idle_time_in_seconds: Int = 90,
    max_bytes_waiting_for_written_per_channel: Int = 64 * 1024,
    default_select_timeout: Int = 30 * 1000,
    enable_fuzzy_scheduler: Boolean = false) extends SelectorRunner(default_select_timeout, enable_fuzzy_scheduler) {

  private val ssc = ServerSocketChannel.open()

  private val io_workers = Array.fill(count_for_reader_writers) {
    new NioSocketReaderWriter(
      channel_hander_factory,
      receive_buffer_size,
      socket_max_idle_time_in_seconds,
      max_bytes_waiting_for_written_per_channel,
      default_select_timeout,
      enable_fuzzy_scheduler)
  }

  protected def do_start(): Unit = {

    io_workers.map { _.start(true) }

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
  protected def stop_roughly(): Unit = {
    safeClose(ssc)
    io_workers.map { _.stop(0) }
    io_workers.map { _.join(0) }
  }
  protected def stop_gracefully(): Boolean = {
    safeClose(ssc)
    val deadline = this.get_stop_deadline()
    val timeout = (deadline - System.currentTimeMillis()).toInt
    io_workers.map { _.stop(timeout) }
    true
  }
  protected def has_remaining_work(): Boolean = {
    io_workers.exists { x => x.getStatus() == SelectorRunner.STARTED }
  }
  protected def before_next_loop(): Unit = {
    //nothing else
  }
  protected def process_selected_key(key: SelectionKey): Unit = {
    if (key.isAcceptable()) {
      val ssc = key.channel().asInstanceOf[ServerSocketChannel]
      val channel = ssc.accept()
      val wrapper = new SocketChannelWrapper(channel)
      accepted_channel_configurator(wrapper)
      try {
        channel.configureBlocking(false)
        val p = Math.abs(channel.hashCode() % io_workers.length)
        if (!io_workers(p).register_socket_channel(channel)) {
          channel.close()
        }
      } catch {
        case ex: Throwable => {
          SelectorRunner.warn(ex, "when key is acceptable.")
          safeClose(channel)
        }
      }

    }
  }

}