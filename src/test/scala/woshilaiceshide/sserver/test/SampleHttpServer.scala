package woshilaiceshide.sserver.test

import woshilaiceshide.sserver.http._
import woshilaiceshide.sserver.nio._
import spray.http._
import spray.http.HttpEntity.apply
import spray.http.StatusCode.int2StatusCode

//to test, use `nc -W 60s -C 127.0.0.1 8181 < ./http-requests.dos.txt`
object SampleHttpServer extends App {

  val log = org.slf4j.LoggerFactory.getLogger(SampleHttpServer.getClass);

  import scala.concurrent._
  val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
  implicit val ec = ExecutionContext.fromExecutor(executor)

  val handler = new HttpChannelHandler {

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
      channel.writeResponse(ping)
      ResponseAction.responseNormally
    }
    private val path_ping = Uri.Path("/ping")

    private def write_404(channel: HttpChannel) = {
      channel.writeResponse { new HttpResponse(404) }
      ResponseAction.responseNormally
    }

    private def write_400(channel: HttpChannel) = {
      channel.writeResponse { new HttpResponse(400) }
      ResponseAction.responseNormally
    }

    private val ping_asynchronously = new HttpResponse(200, HttpEntity(ContentTypes.`text/plain`, "Hello World Asynchronously"))
    private def write_ping_asynchronously(channel: HttpChannel) = {
      //channel.post_to_io_thread { channel.writeResponse(ping_asynchronously) }
      Future { channel.writeResponse(ping_asynchronously) }
      ResponseAction.responseNormally
    }

    private def accept_websocket() = ResponseAction.acceptWebsocket { websocket_demo }

    private def count_0123456789_with_x_times(channel: HttpChannel, times: Int) = {

      ResponseAction.acceptChunking(new ChunkedRequestHandler() {

        def channelIdled(): Unit = { /*do nothing*/ }
        def channelWritable(): Unit = { /*do nothing*/ }
        def channelClosed(): Unit = { /*do nothing*/ }

        private var finished = false
        private def format_error() = {
          finished = true
          channel.writeResponse(new HttpResponse(400, "request entity must in format: 01234567890123456789 ...\r\n"))
        }
        private def ok() = {
          finished = true
          channel.writeResponse(new HttpResponse(200, s"${times} of 0123456789 are received correctly\r\n"))
        }
        private def times_error() = {
          finished = true
          channel.writeResponse(new HttpResponse(400, s"${times} of 0123456789 are expected, but ${already} of ... is received\r\n"))
        }

        private var already: Int = 0
        private var last_digit: Byte = -1
        private def eat_a_digit(d: Byte) = {
          if (last_digit == -1) {
            if (0 == d) {
              last_digit = d
            } else {
              format_error()
            }
          } else if (last_digit == 9) {
            if (0 == d) {
              last_digit = d
            } else {
              format_error()
            }
          } else {
            if (last_digit + 1 == d) {
              last_digit = d
              if (d == 9) {
                already = already + 1
              }
            } else {
              format_error()
            }
          }
        }

        def chunkReceived(chunk: MessageChunk): Unit = {
          val bytes = chunk.data.toByteArray
          log.debug(s"receve from ${channel.remoteAddress}: ${bytes.map { _.toString }.mkString}")
          @scala.annotation.tailrec def eat(i: Int): Unit = {
            if (i < bytes.length && !finished) {
              eat_a_digit(bytes(i))
              eat(i + 1)
            }
          }
          eat(0)
        }
        def chunkEnded(end: ChunkedMessageEnd): Unit = {
          if (!finished) {
            if (already == times) ok() else times_error()
          }
        }
      })
    }

    private def do_not_support_chunked_request(channel: HttpChannel) = {
      println("do not support chunked")
      channel.writeResponse { new HttpResponse(400, "I DOES NOT support chunked request.") }
      ResponseAction.responseNormally
    }

    //wrk -c100 -t2 -d30s -H "Connection: keep-alive" -H "User-Agent: ApacheBench/2.4" -H "Accept: */*"  http://127.0.0.1:8787/ping
    def requestReceived(request: HttpRequest, channel: HttpChannel, classifier: RequestClassifier): ResponseAction = request match {

      case HttpRequest(HttpMethods.GET, uri, _, _, _) if uri.path == path_ping => write_ping(channel)

      //wrk -c100 -t2 -d30s --script=./scripts/pipeline_ping.lua http://127.0.0.1:8787/ping_asynchronously
      case HttpRequest(HttpMethods.GET, Uri.Path("/ping_asynchronously"), _, _, _) => write_ping_asynchronously(channel)

      case x @ HttpRequest(HttpMethods.GET, Uri.Path("/websocket_demo"), _, _, _) => accept_websocket()

      case HttpRequest(HttpMethods.POST, uri, _, _, _) if uri.path.startsWith(Uri.Path("/chunked_request")) => {
        log.debug(s"a new request targeted at ${uri} from ${channel.remoteAddress}")
        import Uri.Path._
        uri.path match {
          case Slash(Segment("chunked_request", Slash(Segment(times, _)))) =>
            count_0123456789_with_x_times(channel, times.toInt)
          case _ =>
            write_400(channel)
        }
      }

      case HttpRequest(HttpMethods.GET, Uri.Path("/chunked_response"), _, _, _) => {
        channel.writeResponse(new ChunkedResponseStart(new HttpResponse(200, "chunked response will start")))
        channel.writeResponse(MessageChunk("this is the 1st chunk\r\n"))
        channel.writeResponse(MessageChunk("this is the 2nd chunk\r\n"))
        channel.writeResponse(MessageChunk("this is the 3rd chunk\r\n"))
        channel.writeResponse(MessageChunk("this is the last chunk\r\n"))
        channel.writeResponse(ChunkedMessageEnd)
        ResponseAction.responseNormally
      }

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
    //buffer_pool_factory = DefaultByteBufferPoolFactory(1, 1, true),
    //buffer_pool_factory = DefaultByteBufferPoolFactory(512, 64, true),
    //more i/o, more asynchronously, then make it bigger
    buffer_pool_factory = DefaultByteBufferPoolFactory(128, 8, true),
    io_thread_factory = new woshilaiceshide.sserver.http.AuxThreadFactory())

  val port = 8787

  val server = NioSocketServer(
    "0.0.0.0",
    port,
    factory,
    configurator)

  server.register_on_termination {
    executor.shutdown()
  }

  server.start(false)

}
