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
    requestProtocol: HttpProtocol, val configurator: HttpConfigurator) extends S2ResponseRenderingComponent {

  def post_to_io_thread(task: Runnable): Boolean = channel.post_to_io_thread(task)
  def post_to_io_thread(task: => Unit): Boolean = channel.post_to_io_thread(new Runnable() { def run = task })

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

  /**
   * 'server' and 'date' headers may be served by proxies (nginx?).
   *
   * to make the best performance, do your best to use the instantiated objects in
   * 'spray.http.ContentTypes' and 'spray.http.MediaTypes' and 'spray.http.ContentTypes.HttpCharsets'.
   *
   * @return a flag of type 'woshilaiceshide.sserver.nio.WriteResult' indicating the bytes are written successfully.
   * this is a IMPORTANT flag!
   *
   * use the returned 'woshilaiceshide.sserver.nio.WriteResult' and 'woshilaiceshide.sserver.http.ResponseAction.responseWithASink(sink: ResponseSink)'
   * for throttling messages.
   *
   * for example, if 'woshilaiceshide.sserver.nio.WriteResult.WR_OK_BUT_OVERFLOWED' is returned, writing should be paused
   * until 'woshilaiceshide.sserver.http.ResponseSink.channelWritable()' is invoked.
   *
   */
  def writeResponse(response: HttpResponsePart, size_hint: Int = 1024, write_server_and_date_headers: Boolean = configurator.write_server_and_date_headers) = {

    val (wr, should_close) = synchronized {

      val _finished = check_finished(response)

      val r = configurator.borrow_bytes_rendering(size_hint, response)
      val ctx = new S2ResponsePartRenderingContext(response, requestMethod, requestProtocol, closeAfterEnd)
      val closeMode = renderResponsePartRenderingContext(r, ctx, akka.event.NoLogging, write_server_and_date_headers)

      //use 'write_even_if_too_busy = true' as intended
      val write_result = channel.write(r.to_byte_buffer(), true, false)

      configurator.return_bytes_rendering(r)

      val close_now = closeMode.shouldCloseNow(ctx.responsePart, closeAfterEnd)
      if (closeMode == CloseMode.CloseAfterEnd) closeAfterEnd = true

      (write_result, close_now)
    }

    if (should_close) channel.closeChannel(false)

    wr
  }

}