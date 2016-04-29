package woshilaiceshide.sserver.nio

import woshilaiceshide.sserver.nio._
import java.nio._

//0. "handler" is the handler that will be used next time
//1. "remaining" is the un-consumed bytes those should be fed to "handler" immediately
final case class HandledResult(handler: TrampledChannelHandler, remaining: ByteBuffer)

trait TrampledChannelHandler {

  def channelOpened(channelWrapper: ChannelWrapper): Unit

  def inputEnded(channelWrapper: ChannelWrapper): Unit

  //the returned HandleResult will be used when the remaining bytes should be fed again.
  //dead locks or too deep recursions are avoided.
  //keep all the contexts for all the "received events" the same.
  def bytesReceived(byteBuffer: ByteBuffer, channelWrapper: ChannelWrapper): HandledResult

  def channelIdled(channelWrapper: ChannelWrapper): Unit

  def channelWritable(channelWrapper: ChannelWrapper): Unit

  def writtenHappened(channelWrapper: ChannelWrapper): TrampledChannelHandler

  def channelClosed(channelWrapper: ChannelWrapper, cause: ChannelClosedCause.Value, attachment: Option[_]): Unit

}

//i am a utility for constructing complicated handlers, and you may avoid dead locks, too deep recursions by using me.  
//note that this class is not thread-safe, and should be instantiated every time.
class Trampling(private var inner: TrampledChannelHandler) extends ChannelHandler {

  def channelOpened(channelWrapper: ChannelWrapper): Unit = {

    if (null != inner) {
      inner.channelOpened(channelWrapper)
    }
  }

  def inputEnded(channelWrapper: ChannelWrapper): Unit = {
    if (null != inner) {
      inner.inputEnded(channelWrapper)
    }
  }

  @scala.annotation.tailrec
  private def continue_to_receive(result: HandledResult, channelWrapper: ChannelWrapper): HandledResult = {

    if (null == result) {
      inner = null
      null
    } else {
      //first set the current handler
      //keep all the "received events" running in the same contexts.
      inner = result.handler

      if (null != result.remaining && 0 < result.remaining.remaining()) {
        continue_to_receive(result.handler.bytesReceived(result.remaining, channelWrapper), channelWrapper)
      } else {
        result
      }
    }
  }

  def bytesReceived(byteBuffer: ByteBuffer, channelWrapper: ChannelWrapper): ChannelHandler = {

    if (null != inner) {
      continue_to_receive(inner.bytesReceived(byteBuffer, channelWrapper), channelWrapper)
      this
    } else {
      this
    }

  }

  def channelIdled(channelWrapper: ChannelWrapper): Unit = {

    if (null != inner) {
      inner.channelIdled(channelWrapper)
    }
  }

  def channelWritable(channelWrapper: ChannelWrapper): Unit = {

    if (null != inner) {
      inner.channelWritable(channelWrapper)
    }
  }

  def writtenHappened(channelWrapper: ChannelWrapper): ChannelHandler = {

    if (null != inner) {
      inner = inner.writtenHappened(channelWrapper)
    }
    this
  }

  def channelClosed(channelWrapper: ChannelWrapper, cause: ChannelClosedCause.Value, attachment: Option[_]): Unit = {

    if (null != inner) {
      inner.channelClosed(channelWrapper, cause, attachment)
    }
  }
}