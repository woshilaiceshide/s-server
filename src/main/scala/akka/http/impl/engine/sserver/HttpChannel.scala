package akka.http.impl.engine.sserver

import akka.http.impl.util._

import akka.http.scaladsl.model.{ HttpResponse => _, _ }
import akka.http.scaladsl.model.headers._

import woshilaiceshide.sserver.http.model._
import woshilaiceshide.sserver.nio._

import scala.annotation._

//won't be reused internally
final class HttpChannel(
    private[http] val channel: ChannelWrapper,
    private[this] var closeAfterEnd: Boolean,
    requestMethod: HttpMethod,
    requestProtocol: HttpProtocol, val configurator: HttpConfigurator) extends S2ResponseRenderingComponent {

  import S2ResponseRenderingComponent._

  def remoteAddress: java.net.SocketAddress = channel.remoteAddress
  def localAddress: java.net.SocketAddress = channel.localAddress

  private val finished = new java.util.concurrent.atomic.AtomicBoolean(false)

  def isCompleted = finished.get()

  private def check_finished(response: HttpResponsePart) = {

    response match {
      case _: HttpResponse => {
        if (!finished.compareAndSet(false, true)) {
          throw new RuntimeException("request is already served. DO NOT DO IT AGAIN!")
        }
        true
      }
      case _: ChunkedMessageEnd => {
        if (!finished.compareAndSet(false, true)) {
          throw new RuntimeException("request is already served. DO NOT DO IT AGAIN!")
        }
        true
      }
      case _ => {
        if (finished.get()) {
          throw new RuntimeException("request is already served. DO NOT DO IT AGAIN!")
        }
        false
      }
    }
  }

  //TODO test chunked responding
  //TODO a cached response build utility.
  /**
   * 'server' and 'date' headers may be served by proxies (nginx?).
   *
   * to make the best performance, do your best to use the instantiated objects in
   * 'spray.http.ContentTypes' and 'spray.http.MediaTypes' and 'spray.http.ContentTypes.HttpCharsets'.
   *
   */
  def writeResponse(response: HttpResponsePart, sizeHint: Int = 1024, write_server_and_date_headers: Boolean = configurator.write_server_and_date_headers) = {

    val (wr, should_close) = synchronized {

      val _finished = check_finished(response)

      val r = configurator.borrow_bytes_rendering(sizeHint, response)
      val ctx = new S2ResponsePartRenderingContext(response, requestMethod, requestProtocol, closeAfterEnd)
      val closeMode = renderResponsePartRenderingContext(r, ctx, akka.event.NoLogging, write_server_and_date_headers)

      //TODO why it's error when finished is false

      //if finished, jump to the next request in the pipelining(if existed)
      val generate_written_event = _finished && configurator.max_request_in_pipeline > 1
      val write_result = channel.write(r.to_byte_buffer(), true, generate_written_event)

      configurator.return_bytes_rendering(r)

      val close_now = closeMode.shouldCloseNow(ctx.responsePart, closeAfterEnd)
      if (closeMode == CloseMode.CloseAfterEnd) closeAfterEnd = true

      (write_result, close_now)
    }

    if (should_close) channel.closeChannel(false)

    wr
  }

}