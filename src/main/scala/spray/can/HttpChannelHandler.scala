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

class HttpChannelHandlerFactory(plain_http_channel_handler: PlainHttpChannelHandler, max_request_in_pipeline: Int = 1) extends ChannelHandlerFactory {

  //HttpTransformer is instantiated every time because the handler is stateful.
  def getHandler(channel: ChannelInformation): Option[ChannelHandler] = {

    val trampling = new Trampling(new HttpTransformer(plain_http_channel_handler, max_request_in_pipeline = max_request_in_pipeline))
    Some(trampling)

  }

}

//won't be reused internally
final class HttpChannelWrapper(
    private[can] val channel: ChannelWrapper,
    private[this] var closeAfterEnd: Boolean) extends ResponseRenderingComponent {

  def remoteAddress: java.net.SocketAddress = channel.remoteAddress
  def localAddress: java.net.SocketAddress = channel.localAddress

  def serverHeaderValue: String = woshilaiceshide.sserver.httpd.HttpdInforamtion.VERSION
  def chunklessStreaming: Boolean = false
  def transparentHeadRequests: Boolean = false

  private var finished = false

  def isCompleted = this.synchronized {
    finished
  }

  private[can] def writeWebSocketResponse(response: HttpResponse) = {
    val wr = synchronized {

      if (finished) {
        throw new RuntimeException("request is already served. DO NOT DO IT AGAIN!")
      }

      val r = new ByteArrayRendering(1024)
      val ctx = new ResponsePartRenderingContext(responsePart = response)
      val closeMode = renderResponsePartRenderingContext(r, ctx, akka.event.NoLogging)

      channel.write(r.get, true, false)
    }

    wr
  }
  def writeResponse(response: HttpResponsePart) = {
    val (_finished, wr, should_close) = synchronized {

      if (finished) {
        throw new RuntimeException("request is already served. DO NOT DO IT AGAIN!")
      }
      response match {
        case _: HttpResponse => finished = true
        case _: ChunkedMessageEnd => finished = true
        case _ => {}
      }

      val r = new ByteArrayRendering(1024)
      val ctx = new ResponsePartRenderingContext(responsePart = response)
      val closeMode = renderResponsePartRenderingContext(r, ctx, akka.event.NoLogging)

      //if finished, continue the next request in the pipelining(if existed)
      val write_result = channel.write(r.get, true, finished)

      val closeNow = closeMode.shouldCloseNow(ctx.responsePart, closeAfterEnd)
      if (closeMode == CloseMode.CloseAfterEnd) closeAfterEnd = true

      (finished, write_result, closeNow)
    }

    if (should_close) channel.closeChannel(false)

    wr
  }

}

trait ChunkedHttpChannelHandler {

  def messageReceived(chunk: MessageChunk): Unit
  def ended(end: ChunkedMessageEnd): Unit
  def inputEnded(): Unit

}

//three cases: 
//1. plain http request, not stateful
//2. chunked request, statueful, but the same parser
//3. websocket, stateful and a different parser/handler needed 
trait PlainHttpChannelHandler {

  def requestReceived(request: HttpRequest, channel: HttpChannelWrapper): Unit

  def chunkedStarted(start: ChunkedRequestStart, channel: HttpChannelWrapper): Option[ChunkedHttpChannelHandler]

  //return a websocket handler instead of websocket transformer, 
  //because the transformer need to be instantiated every time, which will be coded incorrectly by coders.
  def websocketStarted(start: HttpRequest, channel: ChannelInformation): Option[WebSocketChannelWrapper => (WebSocketChannelHandler, WebSocket13.WSFrameParser)]

  def channelWritable(channel: HttpChannelWrapper): Unit

}


