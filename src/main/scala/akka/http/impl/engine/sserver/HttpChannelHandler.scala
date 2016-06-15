package akka.http.impl.engine.sserver

import akka.util._

import akka.http.impl.util._
import woshilaiceshide.sserver.http.model._

import woshilaiceshide.sserver.nio._

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import akka.util.ByteString
import akka.http.impl.engine.parsing._

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._

import StatusCodes._
import HttpProtocols._

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
  def classification(request: woshilaiceshide.sserver.http.model.HttpRequest): RequestClassification.Value
}

private[http] object AChunkedRequestStart extends RequestClassifier {
  def classification(request: woshilaiceshide.sserver.http.model.HttpRequest): RequestClassification.Value = RequestClassification.ChunkedHttpStart
}

private[http] object DynamicRequestClassifier extends RequestClassifier {

  def classification(request: woshilaiceshide.sserver.http.model.HttpRequest): RequestClassification.Value = {
    request match {
      case x if WebSocket13.isAWebSocketRequest(x) => RequestClassification.WebsocketStart
      case _ => RequestClassification.PlainHttp
    }
  }

}

/**
 * low level api. 'PlainRequestHttpChannelHandler' is enough in general.
 */
trait HttpChannelHandler {

  def requestReceived(request: woshilaiceshide.sserver.http.model.HttpRequest, channel: HttpChannel, classifier: RequestClassifier): ResponseAction
}

/**
 * 'PlainRequestHttpChannelHandler' is enough in general. see 'HttpChannelHandler' if not enough.
 */
trait PlainRequestHttpChannelHandler extends HttpChannelHandler {

  def requestReceived(request: woshilaiceshide.sserver.http.model.HttpRequest, channel: HttpChannel): Unit

  final def requestReceived(request: woshilaiceshide.sserver.http.model.HttpRequest, channel: HttpChannel, classifier: RequestClassifier): ResponseAction = {
    requestReceived(request, channel)
    ResponseAction.responseNormally
  }

}


