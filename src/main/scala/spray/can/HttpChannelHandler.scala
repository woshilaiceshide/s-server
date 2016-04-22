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

  val handler = Some(new HttpTransformer(plain_http_channel_handler, max_request_in_pipeline = max_request_in_pipeline))
  def getChannelHandler(aChannel: ChannelInformation): Option[ChannelHandler] = handler

}

final class HttpChannelWrapper(
    channelWrapper: ChannelWrapper,
    private[this] var closeAfterEnd: Boolean,
    private[this] val httpChannelHandler: HttpTransformer) extends ResponseRenderingComponent {

  def serverHeaderValue: String = woshilaiceshide.sserver.httpd.HttpdInforamtion.VERSION
  def chunklessStreaming: Boolean = false
  def transparentHeadRequests: Boolean = false

  private var finished = false

  private[can] def writeWebSocketResponse(response: HttpResponse) = {
    val wr = synchronized {

      if (finished) {
        throw new RuntimeException("request is already served. DO NOT DO IT AGAIN!")
      }

      val r = new ByteArrayRendering(1024)
      val ctx = new ResponsePartRenderingContext(responsePart = response)
      val closeMode = renderResponsePartRenderingContext(r, ctx, akka.event.NoLogging)

      channelWrapper.write(r.get)
    }

    wr
  }
  def writeResponse(response: HttpResponsePart) = {
    val (_finished, wr) = synchronized {

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

      val write_result = channelWrapper.write(r.get)

      val closeNow = closeMode.shouldCloseNow(ctx.responsePart, closeAfterEnd)
      if (closeMode == CloseMode.CloseAfterEnd) closeAfterEnd = true
      if (closeNow) {
        //closeAfterEnd = false
        channelWrapper.closeChannel(false)
      }
      (finished, write_result)
    }

    if (finished) {
      httpChannelHandler.check(channelWrapper, closeAfterEnd)
    }
    wr
  }

  //??? born could be optimized
  def toWebSocketTransformer(request: HttpRequest, extraHeaders: List[HttpHeader], max_payload_length: Int, born: WebSocketChannelWrapper => WebSocketChannelHandler) = {
    WebSocket13.tryAccept(request) match {
      case WebSocketAcceptance.Ok(response) => {
        writeWebSocketResponse(response)
        val channel = new WebSocketChannelWrapper(channelWrapper)
        LengthedWebSocketChannelHandler(born(channel), max_payload_length)
      }
      case WebSocketAcceptance.Failed(response) => {
        writeWebSocketResponse(response)
        channelWrapper.closeChannel(false)
        null
      }
      case WebSocketAcceptance.ERROR => {
        channelWrapper.closeChannel(true)
        null
      }
    }

  }

  def respond(r: => HttpResponse) = new HttpRequestProcessor() {
    def channelWrapper = HttpChannelWrapper.this
    private lazy val r1 = r
    def close() {}
    private var _finished = false
    def finished = _finished
    def channelWritable() = {
      if (!_finished) {
        _finished = HttpChannelWrapper.this.writeResponse(r1) == WriteResult.WR_OK
      }
      _finished
    }
  }

  def respondAsynchronously(r: => scala.concurrent.Future[HttpResponse])(implicit ec: scala.concurrent.ExecutionContext) = new HttpRequestProcessor() {
    def channelWrapper = HttpChannelWrapper.this
    private val r1 = r.recover {
      case _ => HttpResponse(500)
    }
    r1.onSuccess {
      case response => synchronized {
        resp = response
        channelWritable()
      }
    }
    private var resp: HttpResponse = null
    def close() {}
    private var _finished = false
    def finished = synchronized { _finished }
    def channelWritable() = synchronized {
      if (!_finished && resp != null) {
        _finished = HttpChannelWrapper.this.writeResponse(resp) == WriteResult.WR_OK
        _finished
      } else {
        false
      }
    }
  }

}

trait PlainHttpChannelHandler {
  def requestReceived(request: HttpRequestPart, channel: HttpChannelWrapper): HttpRequestProcessor
  def channelClosed(channel: HttpChannelWrapper): Unit
}

final case class LengthedWebSocketChannelHandler(val handler: WebSocketChannelHandler,
    val max_payload_length: Int) extends HttpRequestProcessor {

  def no = throw new RuntimeException("this method should not be invoked anywhere.")
  def channelWrapper: HttpChannelWrapper = no

  override def chunkReceived(x: MessageChunk): Unit = no
  override def chunkEnded(x: ChunkedMessageEnd): Unit = no

  def channelWritable(): Boolean = no
  def close(): Unit = no
  def finished: Boolean = no

}


