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

class HttpChannelHandlerFactory(http_channel_handler: HttpChannelHandler, max_request_in_pipeline: Int = 1) extends ChannelHandlerFactory {

  //HttpTransformer is instantiated every time because the handler is stateful.
  def getHandler(channel: ChannelInformation): Option[ChannelHandler] = {

    val trampling = new Trampling(new HttpTransformer(http_channel_handler, max_request_in_pipeline = max_request_in_pipeline))
    Some(trampling)

  }

}

//won't be reused internally
final class HttpChannel(
    private[can] val channel: ChannelWrapper,
    private[this] var closeAfterEnd: Boolean,
    requestMethod: HttpMethod,
    requestProtocol: HttpProtocol) extends ResponseRenderingComponent {

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

  //TODO test chunked responding
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

      //if finished, jump to the next request in the pipelining(if existed)
      val write_result = channel.write(r.get, true, finished)

      val closeNow = closeMode.shouldCloseNow(ctx.responsePart, closeAfterEnd)
      if (closeMode == CloseMode.CloseAfterEnd) closeAfterEnd = true

      (finished, write_result, closeNow)
    }

    if (should_close) channel.closeChannel(false)

    wr
  }

}

//see 'woshilaiceshide.sserver.nio.ChannelHandler'
trait ResponseSink {

  def channelIdled(channel: HttpChannel): Unit
  def channelWritable(channel: HttpChannel): Unit
  def channelClosed(channel: HttpChannel): Unit
}

trait ChunkedRequestHandler extends ResponseSink {

  def chunkReceived(chunk: MessageChunk): Unit
  def chunkEnded(end: ChunkedMessageEnd): Unit
}

sealed abstract class ResponseAction

//three cases: 
//1. plain http request, not stateful
//2. chunked request, statueful, but the same parser
//3. websocket, stateful and a different parser/handler needed
object ResponseAction {

  private[can] object ResponseNormally extends ResponseAction
  private[can] final case class ResponseWithASink(sink: ResponseSink) extends ResponseAction
  private[can] final case class AcceptWebsocket(factory: WebSocketChannel => (WebSocketChannelHandler, WebSocket13.WSFrameParser)) extends ResponseAction
  private[can] final case class AcceptChunking(handler: ChunkedRequestHandler) extends ResponseAction

  //I'll work with this response finely.
  def responseNormally: ResponseAction = ResponseNormally

  //maybe a chunked response or a big asynchronous response will be sent, 
  //so some sink is help to tweak the related response stream.
  def responseWithASink(sink: ResponseSink): ResponseAction = ResponseWithASink(sink)

  //return a websocket handler instead of websocket transformer, 
  //because the transformer need to be instantiated every time, which will be coded incorrectly by some coders.
  def acceptWebsocket(factory: WebSocketChannel => (WebSocketChannelHandler, WebSocket13.WSFrameParser)): ResponseAction = {
    new AcceptWebsocket(factory)
  }

  //I'll work with this chunked request.
  def acceptChunking(handler: ChunkedRequestHandler): ResponseAction = {
    new AcceptChunking(handler)
  }

}

object RequestClassification extends scala.Enumeration {

  val PlainHttp = Value
  val ChunkedHttpStart = Value
  val WebsocketStart = Value
}

sealed abstract class RequestClassifier {
  def classification: RequestClassification.Value
}

private[can] object AChunkedRequestStart extends RequestClassifier {
  def classification: RequestClassification.Value = RequestClassification.ChunkedHttpStart
}

final case class DynamicRequestClassifier private[can] (request: HttpRequest) extends RequestClassifier {

  lazy val classification: RequestClassification.Value = {
    request match {
      case x if WebSocket13.isAWebSocketRequest(x) => RequestClassification.WebsocketStart
      case _ => RequestClassification.PlainHttp
    }
  }

}

trait HttpChannelHandler {

  def requestReceived(request: HttpRequest, channel: HttpChannel, classifier: RequestClassifier): ResponseAction
}

trait PlainRequestHttpChannelHandler extends HttpChannelHandler {

  def requestReceived(request: HttpRequest, channel: HttpChannel): Unit

  final def requestReceived(request: HttpRequest, channel: HttpChannel, classifier: RequestClassifier): ResponseAction = {
    requestReceived(request, channel)
    ResponseAction.responseNormally
  }

}


