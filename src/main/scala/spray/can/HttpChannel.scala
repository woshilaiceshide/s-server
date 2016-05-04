package spray.can

import spray.util._

import spray.http._
import spray.http.HttpHeaders._

import _root_.spray.can.rendering.ResponsePartRenderingContext
import _root_.spray.can.rendering.ResponseRenderingComponent
import _root_.spray.can.rendering.ResponseRenderingComponent

import woshilaiceshide.sserver.nio._

import scala.annotation._

//won't be reused internally
final class HttpChannel(
    private[can] val channel: ChannelWrapper,
    private[this] var closeAfterEnd: Boolean,
    requestMethod: HttpMethod,
    requestProtocol: HttpProtocol) extends OptimizedResponseRenderingComponent {

  def remoteAddress: java.net.SocketAddress = channel.remoteAddress
  def localAddress: java.net.SocketAddress = channel.localAddress

  private var finished = false

  def isCompleted = this.synchronized { finished }

  //TODO test chunked responding
  /**
   * 'server' and 'date' headers may be served by proxies (nginx?).
   *
   * netty's 'hello world' example does not render 'server' and 'date' headers.
   */
  def writeResponse(response: HttpResponsePart, sizeHint: Int = 1024, writeServerAndDateHeader: Boolean = false) = {

    val (_finished, wr, should_close) = synchronized {

      if (finished) {
        throw new RuntimeException("request is already served. DO NOT DO IT AGAIN!")
      }
      response match {
        case _: HttpResponse => finished = true
        case _: ChunkedMessageEnd => finished = true
        case _ => {}
      }

      val r = new ByteArrayRendering(sizeHint)
      val ctx = new ResponsePartRenderingContext(response, requestMethod, requestProtocol, closeAfterEnd)
      val closeMode = renderResponsePartRenderingContext(r, ctx, akka.event.NoLogging, writeServerAndDateHeader)

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