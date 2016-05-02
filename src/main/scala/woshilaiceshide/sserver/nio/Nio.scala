package woshilaiceshide.sserver.nio

import java.net.InetSocketAddress
import java.util.Iterator

import java.io.Closeable
import java.io.IOException

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels._
import java.nio.charset._

/**
 * some common 
 */
object Nio {

  private[nio] val CHANNEL_NORMAL = 0
  private[nio] val CHANNEL_CLOSING_GRACEFULLY = 1
  private[nio] val CHANNEL_CLOSING_RIGHT_NOW = 2
  private[nio] val CHANNEL_CLOSED = 3

  private[nio] final class MyChannelInformation(channel: SocketChannel) extends ChannelInformation {
    def remoteAddress = channel.getRemoteAddress
    def localAddress = channel.getLocalAddress
  }

  final class ServerSocketChannelWrapper(channel: ServerSocketChannel) {
    def setOption[T](name: java.net.SocketOption[T], value: T) = {
      channel.setOption(name, value)
    }
    private[nio] var backlog: Int = -1
    def setBacklog(backlog: Int) = {
      this.backlog = backlog
    }
  }

  final class SocketChannelWrapper(channel: SocketChannel) {
    def setOption[T](name: java.net.SocketOption[T], value: T) = {
      channel.setOption(name, value)
    }
  }

}