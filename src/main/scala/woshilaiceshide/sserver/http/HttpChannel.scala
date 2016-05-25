package woshilaiceshide.sserver.http

import spray.util._

import spray.http._
import spray.http.HttpHeaders._

import woshilaiceshide.sserver.nio._

import scala.annotation._

//won't be reused internally
final class HttpChannel(
    private[http] val channel: ChannelWrapper,
    private[this] var closeAfterEnd: Boolean,
    requestMethod: HttpMethod,
    requestProtocol: HttpProtocol, val configurator: HttpConfigurator) extends ResponseRenderingComponent {

  import ResponseRenderingComponent._

  def remoteAddress: java.net.SocketAddress = channel.remoteAddress
  def localAddress: java.net.SocketAddress = channel.localAddress

  private var finished = false

  def isCompleted = this.synchronized { finished }

  //TODO test chunked responding
  //TODO a cached response build utility.
  /**
   * 'server' and 'date' headers may be served by proxies (nginx?).
   *
   * to make the best performance, do your best to use the instantiated objects in
   * 'spray.http.ContentTypes' and 'spray.http.MediaTypes' and 'spray.http.ContentTypes.HttpCharsets'.
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

      //val r = new RevisedByteArrayRendering(sizeHint)
      val r = configurator.borrow_bytes_rendering(sizeHint, response)
      val ctx = new ResponsePartRenderingContext(response, requestMethod, requestProtocol, closeAfterEnd)
      val closeMode = renderResponsePartRenderingContext(r, ctx, akka.event.NoLogging, writeServerAndDateHeader)

      //TODO why it's error when finished is false

      //if finished, jump to the next request in the pipelining(if existed)
      val generate_writing_event = finished && configurator.max_request_in_pipeline > 1
      val write_result = channel.write(r.get_underlying_array(), r.get_underlying_offset(), r.get_underlying_size(), true, generate_writing_event)

      configurator.return_bytes_rendering(r)

      val closeNow = closeMode.shouldCloseNow(ctx.responsePart, closeAfterEnd)
      if (closeMode == CloseMode.CloseAfterEnd) closeAfterEnd = true

      (finished, write_result, closeNow)
    }

    if (should_close) channel.closeChannel(false)

    wr
  }

}