package woshilaiceshide.sserver

import java.net.InetSocketAddress
import java.util.Iterator

import java.io.Closeable
import java.io.IOException

import java.nio.ByteBuffer
import java.nio.channels._

import scala.annotation.tailrec

//public 'api's here
package object nio {

  object ChannelClosedCause extends scala.Enumeration {
    val UNKNOWN = Value
    //the upper business codes closed the channel
    val BY_BIZ = Value
    val SERVER_STOPPING = Value
    val BY_PEER = Value
    val BECUASE_INPUTSTREAM_ENDED = Value
    val BECUASE_SOCKET_CLOSED_UNEXPECTED = Value
    val BECAUSE_IDLE = Value
  }

  object WriteResult extends scala.Enumeration {
    val WR_OK = Value

    //commented, this value makes no sense in practice.
    //written succeeded, but the inner buffer pool is full(overflowed in fact), which will make the next writing failed definitely.
    //wait for 'def channelWritable(...)' if this value encountered.
    //val WR_OK_BUT_OVERFLOWED = Value

    val WR_FAILED_BECAUSE_TOO_MANY_WRITES_EXISTED = Value
    val WR_FAILED_BECAUSE_CHANNEL_CLOSED = Value
    val WR_FAILED_BECAUSE_EMPTY_BYTES_TO_WRITTEN = Value
  }

  trait ChannelWrapper {

    //if rightNow is false, then close gracefully.
    def closeChannel(rightNow: Boolean = false, attachment: Option[_] = None): Unit

    def is_in_io_worker_thread(): Boolean
    def post(task: Runnable): Boolean

    def remoteAddress: java.net.SocketAddress
    def localAddress: java.net.SocketAddress

    //Only the writing to the channel is taken into account when calculating the idle-time-out by default.
    //So if transferring big files, such as in http chunking requests that last long time, use resetIdle().
    def resetIdle(): Unit

    override def toString() = s"""${remoteAddress}->${localAddress}@@${hashCode}}"""

    //if bytes that are already waiting for written is more than max_bytes_waiting_for_written_per_channel, 
    //then no bytes will be written, except for write_even_if_too_busy is true.
    //buf after this method's execution, byte waiting for written may be more than max_bytes_waiting_for_written_per_channel.
    //if you are a lazy coder, then use true for write_even_if_too_busy.
    //all bytes are written successfully, or none written(no partial written).
    def write(bytes: Array[Byte], write_even_if_too_busy: Boolean = false): WriteResult.Value
  }

  //this trait is full of sinks. Every sink receives a channel wrapper, 
  //so that business codes could leave the channel wrapper off.
  trait ChannelHandler {

    def channelOpened(channelWrapper: ChannelWrapper): Unit
    //business codes may "ping" to find out weather the peer is fine, or just shutdown the whole socket in this situation.
    def inputEnded(channelWrapper: ChannelWrapper): Unit
    //byteBuffer can not be changed by the method body's point of view(aka, readonly in the its point of view), 
    //but may be changed by the underlying socket server, 
    //because byteBuffer's inner array is shared with the writable buffer used by the underlying socket server. 
    def bytesReceived(byteBuffer: ByteBuffer, channelWrapper: ChannelWrapper): ChannelHandler
    //if the handler does not close the channel, then the channel will closed roughly. 
    def channelIdled(channelWrapper: ChannelWrapper): Unit
    //the previous transport is paused because of too many bytes waiting for transport. 
    //now please continue your transport.
    //this sink is important for throttling.
    def channelWritable(channelWrapper: ChannelWrapper): Unit
    def channelClosed(channelWrapper: ChannelWrapper, cause: ChannelClosedCause.Value, attachment: Option[_]): Unit
  }

  //information about channels. DO NOT use me to execute operations on channels.
  final class ChannelInformation(channel: SocketChannel) {
    def remoteAddress = channel.getRemoteAddress
    def localAddress = channel.getLocalAddress
  }

  trait ChannelHandlerFactory {
    def getChannelHandler(aChannel: ChannelInformation): Option[ChannelHandler]
  }

}