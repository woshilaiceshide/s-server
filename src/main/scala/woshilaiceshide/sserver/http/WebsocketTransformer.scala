package woshilaiceshide.sserver.http

import akka.util._

import _root_.spray.http._
import _root_.spray.http.HttpRequest
import _root_.spray.http.HttpResponse
import _root_.spray.http.StatusCodes
import _root_.spray.http.HttpHeader
import _root_.spray.http.ByteArrayRendering
import _root_.spray.http.HttpResponsePart
import _root_.spray.http.HttpRequestPart

import woshilaiceshide.sserver.nio._

object WebSocketChannel {
  private val ResponseStatus_Init: Byte = 0
  private val ResponseStatus_Accepted: Byte = 1
  private val ResponseStatus_Failed: Byte = 2
  private val ResponseStatus_Refused: Byte = 4
  private val ResponseStatus_Ended: Byte = 8
}

class WebSocketChannel(channel: ChannelWrapper,
                       private[this] var closeAfterEnd: Boolean,
                       requestMethod: HttpMethod,
                       requestProtocol: HttpProtocol, val configurator: HttpConfigurator) extends S2ResponseRenderingComponent {

  import WebSocketChannel._
  import WebSocket13._

  private var response_status: Byte = 0

  def is_accepted() = this.synchronized { this.response_status == ResponseStatus_Accepted }

  def maxResponseSize: Int = configurator.max_response_size

  def tryAccept(request: HttpRequest, extraHeaders: List[HttpHeader] = Nil, cookies: List[HttpCookie] = Nil): Boolean = {

    WebSocket13.tryAccept(request, extraHeaders, cookies) match {
      case WebSocketAcceptance.Failed(response) => {

        val continued = this.synchronized {
          if (this.response_status == ResponseStatus_Init) {
            this.response_status = ResponseStatus_Failed
            true
          } else {
            false
          }
        }
        if (continued) {
          writeWebSocketResponse(response)
          //just close it
          //if (closeAfterEnd) {
          channel.closeChannel(false)
          //}
        }
        false
      }
      case WebSocketAcceptance.Ok(response) => {

        val continued = this.synchronized {
          if (this.response_status == ResponseStatus_Init) {
            this.response_status = ResponseStatus_Accepted
            true
          } else {
            false
          }
        }
        if (continued) {
          writeWebSocketResponse(response)
          //if (closeAfterEnd) {
          //  channel.closeChannel(false)
          //}
        }
        true

      }
    }

  }

  def refuse(response: HttpResponse) = {
    val continued = this.synchronized {
      if (this.response_status == ResponseStatus_Init) {
        this.response_status = ResponseStatus_Refused
        true
      } else {
        false
      }
    }

    if (continued) {
      writeWebSocketResponse(response)
      //just close it
      //if (closeAfterEnd) {
      channel.closeChannel(false)
      //}
    }
  }

  private def writeWebSocketResponse(response: HttpResponse, writeServerAndDateHeader: Boolean = false) = {

    val r = configurator.borrow_bytes_rendering(maxResponseSize, response)
    val ctx = new S2ResponsePartRenderingContext(response, requestMethod, requestProtocol, closeAfterEnd)
    val closeMode = renderResponsePartRenderingContext(r, ctx, akka.event.NoLogging, writeServerAndDateHeader)
    val closeNow = closeMode.shouldCloseNow(ctx.responsePart, closeAfterEnd)
    if (closeMode == S2ResponseRenderingComponent.CloseMode.CloseAfterEnd) closeAfterEnd = true

    channel.write(r.to_byte_buffer(), true)
    configurator.return_bytes_rendering(r)

  }

  def writeString(s: String) = {
    val rendered = render(s)
    channel.write(rendered.toArray, true, false)
  }
  def writeBytes(bytes: Array[Byte]) = {
    val rendered = render(bytes, WebSocket13.OpCode.BINARY)
    channel.write(rendered.toArray, true, false)
  }
  def close(closeCode: Option[CloseCode.Value] = CloseCode.NORMAL_CLOSURE_OPTION) = {

    val continued = this.synchronized {
      if (this.response_status == ResponseStatus_Accepted) {
        this.response_status = ResponseStatus_Ended
        true
      } else {
        false
      }
    }

    if (continued) {
      val frame = WSClose(closeCode.getOrElse(CloseCode.NORMAL_CLOSURE), why(null), EMPTY_BYTE_ARRAY, true, false, EMPTY_BYTE_ARRAY)
      val rendered = render(frame)
      channel.write(rendered.toArray, true, false)
      channel.closeChannel(false, closeCode)
    }
  }
  def ping() = {
    val rendered = render(WebSocket13.EMPTY_BYTE_ARRAY, WebSocket13.OpCode.PING)
    channel.write(rendered.toArray, true, false)
  }
}

//you would WebSocketChannel may be supplied in each sink, 
//but factory should not be optimized in this way, in which situation, api will be ugly.
trait WebSocketChannelHandler {

  def idled(): Unit = {}
  def pongReceived(frame: WebSocket13.WSFrame): Unit
  def frameReceived(frame: WebSocket13.WSFrame): Unit
  def fireClosed(code: WebSocket13.CloseCode.Value, reason: String): Unit
  def inputEnded(): Unit

  def channelWritable(): Unit
}

class WebsocketTransformer(
  handler: WebSocketChannelHandler, channel: WebSocketChannel,
  configurator: HttpConfigurator)
    extends ChannelHandler {

  private[this] var parser: WebSocket13.WSFrameParser = configurator.get_websocket_parser()

  //already opened
  final def channelOpened(channelWrapper: ChannelWrapper): Unit = {}

  def inputEnded(channelWrapper: ChannelWrapper) = handler.inputEnded()

  def customizedObjectReceived(obj: AnyRef, channelWrapper: ChannelWrapper): Unit = {}

  //TODO to be optimized
  def bytesReceived(byteBuffer: java.nio.ByteBuffer, channelWrapper: ChannelWrapper): ChannelHandler = {

    val byteString = ByteString(byteBuffer)
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
              //just close it!
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
        case WSResult.End                      => { /* nothing to do */ null /*this*/ }
        case WSResult.Error(closeCode, reason) => { channelWrapper.closeChannel(false); null /*this*/ }
      }
    }
    process(result)

  }

  def channelIdled(channelWrapper: ChannelWrapper): Unit = handler.idled()

  def channelWritable(channelWrapper: ChannelWrapper): Unit = handler.channelWritable()

  def channelClosed(channelWrapper: ChannelWrapper, cause: ChannelClosedCause.Value, attachment: Option[_]): Unit = {
    cause match {
      case ChannelClosedCause.BY_BIZ => {
        val closeCode = attachment match {
          case Some(code: WebSocket13.CloseCode.Value) => code
          case _                                       => WebSocket13.CloseCode.CLOSED_ABNORMALLY
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