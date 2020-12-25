package woshilaiceshide.sserver.nio

import java.net.InetSocketAddress
import java.util.Iterator

import java.io.Closeable
import java.io.IOException

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels._
import java.nio.charset._

import java.nio.channels.SelectionKey._

import scala.annotation.tailrec

import SelectorRunner._

/**
 * a nio socket server in multi-threads.
 * @see woshilaiceshide.sserver.nio.NioSocketServer1
 */
class NioSocketAcceptor private[nio] (
                                       interface: String,
                                       port: Int,
                                       channel_handler_factory: ChannelHandlerFactory,
                                       configurator: NioConfigurator) extends SelectorRunner(configurator) {

  import configurator._

  private val ssc = ServerSocketChannel.open()

  private val io_workers = Array.fill(count_for_reader_writers) {
    new NioSocketReaderWriter(channel_handler_factory, configurator)
  }

  protected def do_start(): Unit = {

    io_workers.map {
      _.register_on_termination {
        this.stop(-1)
      }
    }

    io_workers.map { _.start(true) }

    val wrapper = new ServerSocketChannelWrapper(ssc)
    listening_channel_configurator(wrapper)
    SelectorRunner.log.info(s"binding to ${interface}:${port}, backlog is ${wrapper.backlog}")
    if (-1 == wrapper.backlog) {
      ssc.socket().bind(new InetSocketAddress(interface, port))
    } else {
      ssc.socket().bind(new InetSocketAddress(interface, port), wrapper.backlog)
    }

    ssc.configureBlocking(false)
    this.register(ssc, SelectionKey.OP_ACCEPT, null)
  }

  protected def add_a_new_socket_channel(channel: SocketChannel): Unit = {}

  protected def stop_roughly(): Unit = {
    safe_close(ssc)
    io_workers.map { _.stop(-1) }
  }
  protected def stop_gracefully(): Boolean = {
    safe_close(ssc)
    val deadline = this.get_stop_deadline()
    val timeout = (deadline - System.currentTimeMillis()).toInt
    io_workers.map { _.stop(timeout) }
    true
  }
  protected def has_remaining_work(): Boolean = {
    io_workers.exists { x => x.get_status() == SelectorRunner.STARTED }
  }
  protected def before_next_loop(): Unit = {
    //nothing else
  }
  override def join(timeout: Long): Unit = {
    super.join(timeout)
    io_workers.map { _.join(timeout) }
  }
  private var i = 0
  protected def process_selected_key(key: SelectionKey, ready_ops: Int): Unit = {
    if ((ready_ops & OP_ACCEPT) > 0) {
      val ssc = key.channel().asInstanceOf[ServerSocketChannel]
      val channel = ssc.accept()
      try {
        i = i + 1
        val p = Math.abs(i % io_workers.length)
        if (!io_workers(p).register_socket_channel(channel)) {
          ssc.close()
        }
      } catch {
        case ex: Throwable => {
          SelectorRunner.log.warn("when key is acceptable", ex)
          safe_close(ssc)
        }
      }

    }
  }

}