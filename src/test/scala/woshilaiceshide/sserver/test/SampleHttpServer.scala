package woshilaiceshide.sserver.test

import woshilaiceshide.sserver.http._
import woshilaiceshide.sserver.nio._
import spray.http._
import spray.http.HttpEntity.apply
import spray.http.StatusCode.int2StatusCode

//to test, use `nc -C 127.0.0.1 8181 < ./http-requests.dos.txt`
object SampleHttpServer extends App {

  val handler = new HttpChannelHandler {

    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent._

    private val websocket_demo = (c: WebSocketChannel) => {

      new WebSocketChannelHandler() {

        def frameReceived(frame: WebSocket13.WSFrame): Unit = {
          import WebSocket13._
          frame match {
            case x: WSText => {
              if (x.text == "quit") c.close()
              else c.writeString(x.text)
            }
            case _ => c.close(Some(WebSocket13.CloseCode.CAN_NOT_ACCEPT_THE_TYPE_OF_DATA))
          }

        }

        override def idled(): Unit = {}
        def pongReceived(frame: WebSocket13.WSFrame): Unit = { println("ping ok") }
        def fireClosed(code: WebSocket13.CloseCode.Value, reason: String): Unit = {}
        def inputEnded(): Unit = { c.close(WebSocket13.CloseCode.NORMAL_CLOSURE_OPTION) }
        def channelWritable(): Unit = {}
      }

    }

    private val ping = new HttpResponse(200, HttpEntity(ContentTypes.`text/plain`, "Hello World"))
    private def write_ping(channel: HttpChannel) = {
      channel.writeResponse { ping }
      ResponseAction.responseNormally
    }
    private val path_ping = Uri.Path("/ping")

    private def write_404(channel: HttpChannel) = {
      channel.writeResponse { new HttpResponse(400) }
      ResponseAction.responseNormally
    }

    private def write_ping_asynchronously(channel: HttpChannel) = {
      Future {
        Thread.sleep(3 * 1000);
        channel.writeResponse(new HttpResponse(200, "pong0\r\n"))
      }
      ResponseAction.responseNormally
    }

    private def accept_websocket() = ResponseAction.acceptWebsocket { websocket_demo }

    private def do_not_support_chunked_request(channel: HttpChannel) = {
      channel.writeResponse { new HttpResponse(400, "I DOES NOT support chunked request.") }
      ResponseAction.responseNormally
    }

    def requestReceived(request: HttpRequest, channel: HttpChannel, classifier: RequestClassifier): ResponseAction = request match {

      case HttpRequest(HttpMethods.GET, uri, _, _, _) if uri.path == path_ping => write_ping(channel)

      case HttpRequest(HttpMethods.GET, Uri.Path("/ping_asynchronously"), _, _, _) => write_ping_asynchronously(channel)

      case x @ HttpRequest(HttpMethods.GET, Uri.Path("/websocket_demo"), _, _, _) => accept_websocket()

      case x: HttpRequest if classifier.classification(x) == RequestClassification.ChunkedHttpStart => do_not_support_chunked_request(channel)

      case _: HttpRequest => write_404(channel)
    }

  }

  val http_configurator = new HttpConfigurator(max_request_in_pipeline = 8, use_direct_byte_buffer_for_cached_bytes_rendering = false)

  val factory = new HttpChannelHandlerFactory(handler, http_configurator)

  val listening_channel_configurator: ServerSocketChannelWrapper => Unit = wrapper => {
    wrapper.setOption[java.lang.Boolean](java.net.StandardSocketOptions.SO_REUSEADDR, true)
    wrapper.setBacklog(1024 * 8)
  }

  val accepted_channel_configurator: SocketChannelWrapper => Unit = wrapper => {
    wrapper.setOption[java.lang.Boolean](java.net.StandardSocketOptions.TCP_NODELAY, true)
  }

  /*
  val threadFactory = new java.util.concurrent.ThreadFactory() {
    def newThread(r: Runnable) = {
      new Thread(r)
    }
  }
  val mt = new MultipleThreadHandlerFactory(1, threadFactory, Integer.MAX_VALUE, factory)
  */

  val configurator = XNioConfigurator(count_for_reader_writers = 2,
    listening_channel_configurator = listening_channel_configurator,
    accepted_channel_configurator = accepted_channel_configurator,
    buffer_pool_factory = DefaultByteBufferPoolFactory(1, 1))

  val port = 8787

  val server = NioSocketServer(
    "0.0.0.0",
    port,
    factory,
    configurator)

  println(s"starting on: ${port}...")
  server.start(false)

}
