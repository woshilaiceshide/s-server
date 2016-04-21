package spray.can

import akka.util._

import _root_.spray.can.parsing.ParserSettings
import _root_.spray.can.parsing.HttpRequestPartParser
import _root_.spray.can.parsing.Result
import _root_.spray.http._
import _root_.spray.http.HttpRequest
import _root_.spray.http.HttpResponse
import _root_.spray.http.StatusCodes
import _root_.spray.http.HttpHeader
import _root_.spray.http.ByteArrayRendering
import _root_.spray.http.HttpResponsePart
import _root_.spray.http.HttpRequestPart
import _root_.spray.can.rendering.ResponsePartRenderingContext
import _root_.spray.can.rendering.ResponseRenderingComponent
import _root_.spray.can.rendering.ResponseRenderingComponent

import woshilaiceshide.sserver.nio._
import woshilaiceshide.sserver.httpd.WebSocket13
import woshilaiceshide.sserver.httpd.WebSocket13.WebSocketAcceptance

class WebsocketTransformer(
  handler: WebSocketChannelHandler, channel: WebSocketChannelWrapper,
  private[this] var parser: WebSocket13.WSFrameParser)
    extends ChannelHandler {

  def channelOpened(channelWrapper: ChannelWrapper): Unit = {}

  def inputEnded(channelWrapper: ChannelWrapper) = handler.inputEnded()

  def bytesReceived(byteString: ByteString, offset: Int, channelWrapper: ChannelWrapper): ChannelHandler = {
    import WebSocket13._
    val result = parser(byteString)
    @scala.annotation.tailrec def process(result: WSResult): ChannelHandler = {
      result match {
        case WSResult.Emit(frame, continue) => {
          frame match {
            case x: WSPong => {
              handler.pongReceived(x)
              process(continue())
            }
            case x: WSClose => {
              handler.frameReceived(x)
              channelWrapper.closeChannel(false, CloseCode.NORMAL_CLOSURE_OPTION)
              this
            }
            case x => { handler.frameReceived(x); process(continue()) }
          }

        }
        case WSResult.NeedMoreData(parser1) => {
          parser = parser1
          this
        }
        case WSResult.End => { /* nothing to do */ null /*this*/ }
        case WSResult.Error(closeCode, reason) => { channelWrapper.closeChannel(false); null /*this*/ }
      }
    }
    process(result)
  }
  def bytesReceived(byteBuffer: java.nio.ByteBuffer, channelWrapper: ChannelWrapper): ChannelHandler = {

    val byteString = ByteString(byteBuffer)
    bytesReceived(byteString, 0, channelWrapper)

  }
  def channelIdled(channelWrapper: ChannelWrapper): Unit = { handler.idled() }
  def channelWritable(channelWrapper: ChannelWrapper): Unit = {
    if (null != handler) handler.channelWritable()
  }
  def channelClosed(channelWrapper: ChannelWrapper, cause: ChannelClosedCause.Value, attachment: Option[_]): Unit = {
    cause match {
      case ChannelClosedCause.BY_BIZ => {
        val closeCode = attachment match {
          case Some(code: WebSocket13.CloseCode.Value) => code
          case _ => WebSocket13.CloseCode.CLOSED_ABNORMALLY
        }
        handler.fireClosed(closeCode, cause.toString())
      }

      case ChannelClosedCause.SERVER_STOPPING =>
        handler.fireClosed(WebSocket13.CloseCode.GOING_AWAY, cause.toString())
      case _ =>
        handler.fireClosed(WebSocket13.CloseCode.CLOSED_ABNORMALLY, cause.toString())
    }

  }
}