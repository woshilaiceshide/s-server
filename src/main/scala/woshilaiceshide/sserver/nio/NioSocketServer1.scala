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

import woshilaiceshide.sserver.utility.Utility

import SelectorRunner._

/**
 * a nio socket server in a single thread.
 */
class NioSocketServer1 private[nio] (
  interface: String,
  port: Int,
  channel_hander_factory: ChannelHandlerFactory,
  configurator: NioConfigurator)
    extends NioSocketReaderWriter(channel_hander_factory, configurator) {

  private val ssc = ServerSocketChannel.open()

  protected override def do_start(): Unit = {

    super.do_start()

    val wrapper = new ServerSocketChannelWrapper(ssc)
    configurator.listening_channel_configurator(wrapper)
    if (-1 == wrapper.backlog) {
      ssc.socket().bind(new InetSocketAddress(interface, port))
    } else {
      ssc.socket().bind(new InetSocketAddress(interface, port), wrapper.backlog)
    }

    ssc.configureBlocking(false)
    this.register(ssc, SelectionKey.OP_ACCEPT, null)

  }
  protected override def stop_roughly(): Unit = {
    safe_close(ssc)
    super.stop_roughly()

  }
  protected override def stop_gracefully(): Boolean = {
    safe_close(ssc)
    super.stop_gracefully()
  }
  protected override def has_remaining_work(): Boolean = {
    super.has_remaining_work()
  }
  protected override def before_next_loop(): Unit = {
    super.before_next_loop()
  }
  protected override def process_selected_key(key: SelectionKey, ready_ops: Int): Unit = {
    if ((ready_ops & OP_ACCEPT) > 0) {
      val ssc = key.channel().asInstanceOf[ServerSocketChannel]
      val channel = ssc.accept()
      val wrapper = new SocketChannelWrapper(channel)
      configurator.accepted_channel_configurator(wrapper)
      try {
        channel.configureBlocking(false)
        add_a_new_socket_channel(channel)
      } catch {
        case ex: Throwable => {
          SelectorRunner.warn(ex, "when a new channel is accepted.")
          safe_close(channel)
        }
      }
    } else {
      super.process_selected_key(key, ready_ops)
    }
  }

}