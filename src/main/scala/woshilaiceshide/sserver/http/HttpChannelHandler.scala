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

class HttpChannelHandlerFactory(http_channel_handler: HttpChannelHandler, configurator: HttpConfigurator) extends ChannelHandlerFactory {

  //HttpTransformer is instantiated every time because the handler is stateful.
  def getHandler(channel: ChannelInformation): Option[ChannelHandler] = {

    val transformer = new HttpTransformer(http_channel_handler, configurator)
    Some(transformer)

  }

}

//see 'woshilaiceshide.sserver.nio.ChannelHandler'
trait ResponseSink {

  //no channel in sinks as intended
  def channelIdled(): Unit
  def channelWritable(): Unit
  def channelClosed(): Unit
}

trait ChunkedRequestHandler extends ResponseSink {

  //no channel in sinks as intended
  def chunkReceived(chunk: MessageChunk): Unit
  def chunkEnded(end: ChunkedMessageEnd): Unit
}

sealed abstract class ResponseAction

//three cases: 
//1. plain http request, not stateful
//2. chunked request, statueful, but the same parser
//3. websocket, stateful and a different parser/handler needed
object ResponseAction {

  private[http] final case class ResponseWithThis(response: HttpResponse) extends ResponseAction
  private[http] object ResponseNormally extends ResponseAction
  private[http] final case class ResponseWithASink(sink: ResponseSink) extends ResponseAction
  private[http] final case class AcceptWebsocket(factory: WebSocketChannel => WebSocketChannelHandler) extends ResponseAction
  private[http] final case class AcceptChunking(handler: ChunkedRequestHandler) extends ResponseAction

  //I'll work with this response finely.
  def responseNormally: ResponseAction = ResponseNormally

  //maybe a chunked response or a big asynchronous response will be sent, 
  //so some sink is help to tweak the related response stream.
  def responseWithASink(sink: ResponseSink): ResponseAction = ResponseWithASink(sink)

  //return a websocket handler instead of websocket transformer, 
  //because the transformer need to be instantiated every time, which will be coded incorrectly by some coders.
  def acceptWebsocket(factory: WebSocketChannel => WebSocketChannelHandler): ResponseAction = {
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
  def classification(request: HttpRequest): RequestClassification.Value
}

private[http] object AChunkedRequestStart extends RequestClassifier {
  def classification(request: HttpRequest): RequestClassification.Value = RequestClassification.ChunkedHttpStart
}

private[http] object DynamicRequestClassifier extends RequestClassifier {

  def classification(request: HttpRequest): RequestClassification.Value = {
    request match {
      case x if WebSocket13.isAWebSocketRequest(x) => RequestClassification.WebsocketStart
      case _                                       => RequestClassification.PlainHttp
    }
  }

}

/**
 * low level api. 'PlainRequestHttpChannelHandler' is enough in general.
 */
trait HttpChannelHandler {

  def requestReceived(request: HttpRequest, channel: HttpChannel, classifier: RequestClassifier): ResponseAction
}

/**
 * 'PlainRequestHttpChannelHandler' is enough in general. see 'HttpChannelHandler' if not enough.
 */
trait PlainRequestHttpChannelHandler extends HttpChannelHandler {

  def requestReceived(request: HttpRequest, channel: HttpChannel): Unit

  final def requestReceived(request: HttpRequest, channel: HttpChannel, classifier: RequestClassifier): ResponseAction = {
    requestReceived(request, channel)
    ResponseAction.responseNormally
  }

}


