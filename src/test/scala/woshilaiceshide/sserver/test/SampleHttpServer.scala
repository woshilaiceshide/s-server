package woshilaiceshide.sserver.test

import woshilaiceshide.sserver.http._
import woshilaiceshide.sserver.nio._
import spray.http._
import spray.http.HttpEntity.apply
import spray.http.StatusCode.int2StatusCode

/**
 * A http server with plain http.
 *
 * To test it, use `nc -W 60s -C 127.0.0.1 8181 < ./http-requests.dos.txt`
 */
object SampleHttpServer extends App {

  val log = org.slf4j.LoggerFactory.getLogger(SampleHttpServer.getClass);

  val handler = new HttpChannelHandler {

    private val ping = new HttpResponse(200, HttpEntity(ContentTypes.`text/plain`, "Hello, World!"))
    private def write_ping(channel: HttpChannel) = {
      ResponseAction.responseWithThis(ping, size_hint = 128, false)
    }
    private val path_ping = Uri.Path("/ping")

    private def write_404(channel: HttpChannel) = {
      channel.writeResponse { new HttpResponse(404) }
      ResponseAction.responseByMyself
    }

    //wrk -c100 -t2 -d30s -H "Connection: keep-alive" -H "User-Agent: ApacheBench/2.4" -H "Accept: */*"  http://127.0.0.1:8787/ping
    def requestReceived(request: HttpRequest, channel: HttpChannel, classifier: RequestClassifier): ResponseAction = request match {

      case HttpRequest(HttpMethods.GET, uri, _, _, _) if uri.path == path_ping     => write_ping(channel)

      case _: HttpRequest => write_404(channel)
    }

  }

  val http_configurator = new HttpConfigurator(max_request_in_pipeline = 1024, use_direct_byte_buffer_for_cached_bytes_rendering = false)

  val factory = new HttpChannelHandlerFactory(handler, http_configurator)

  val listening_channel_configurator: ServerSocketChannelWrapper => Unit = wrapper => {
    wrapper.setOption[java.lang.Boolean](java.net.StandardSocketOptions.SO_REUSEADDR, true)
    wrapper.setBacklog(1024 * 8 * 10)
  }

  val accepted_channel_configurator: SocketChannelWrapper => Unit = wrapper => {
    wrapper.setOption[java.lang.Boolean](java.net.StandardSocketOptions.TCP_NODELAY, true)
  }
  val configurator = XNioConfigurator(count_for_reader_writers = 0,
    listening_channel_configurator = listening_channel_configurator,
    accepted_channel_configurator = accepted_channel_configurator,
    buffer_pool_factory = DefaultByteBufferPoolFactory(512, 1024, true),
    receive_buffer_size = 1024 * 1024,
    try_to_optimize_selector_key_set = true,
    io_thread_factory = new woshilaiceshide.sserver.http.AuxThreadFactory())

  val port = 8787

  val server = NioSocketServer(
    "0.0.0.0",
    port,
    factory,
    configurator)

  server.start(false)

}
