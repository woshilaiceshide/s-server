package woshilaiceshide.sserver

import java.net.InetSocketAddress
import java.util.Iterator

import java.io.Closeable
import java.io.IOException

import java.nio.ByteBuffer
import java.nio.channels._

import scala.annotation.tailrec

/**
 * private 'api's here
 */
package object nio {

  private[nio] val CHANNEL_UNKNOWN = -1
  private[nio] val CHANNEL_NORMAL = 0
  private[nio] val CHANNEL_CLOSING_GRACEFULLY = 1
  private[nio] val CHANNEL_CLOSING_RIGHT_NOW = 2
  private[nio] val CHANNEL_CLOSED = 3

  private[nio] final class MyChannelInformation(channel: SocketChannel) extends ChannelInformation {
    def remoteAddress = channel.getRemoteAddress
    def localAddress = channel.getLocalAddress
  }

}
