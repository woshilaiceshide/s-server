package woshilaiceshide.sserver

import woshilaiceshide.sserver.httpd._
import woshilaiceshide.sserver.nio._
import WebSocket13.WSText

import spray.can._
import spray.http._
import spray.http.HttpEntity.apply
import spray.http.StatusCode.int2StatusCode

//to test, use `nc -C 127.0.0.1 8181 < ./http-requests.dos.txt`
object SampleHttpServer extends App {

  val handler = new HttpChannelHandler {

    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent._

    private val websocket_demo = (c: WebSocketChannel) => {

      val handler = new WebSocketChannelHandler() {

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

      (handler, WebSocket13.default_parser(2048))
    }

    private val ping1 = new HttpResponse(200, "pong1\r\n")
    def requestReceived(request: HttpRequest, channel: HttpChannel, classifier: RequestClassifier): ResponseAction = request match {
      case HttpRequest(HttpMethods.GET, Uri.Path("/ping1"), _, _, _) => {
        channel.writeResponse {
          ping1
        }
        ResponseAction.responseNormally
      }
      case HttpRequest(HttpMethods.GET, Uri.Path("/ping0"), _, _, _) => {
        Future {
          Thread.sleep(3 * 1000);
          channel.writeResponse(new HttpResponse(200, "pong0\r\n"))
        }
        ResponseAction.responseNormally
      }
      case x @ HttpRequest(HttpMethods.GET, Uri.Path("/websocket_demo"), _, _, _) => {

        ResponseAction.acceptWebsocket { websocket_demo }
      }
      case _: HttpRequest if classifier.classification == RequestClassification.ChunkedHttpStart => {
        channel.writeResponse { new HttpResponse(400, "I DOES NOT support chunked request.") }
        ResponseAction.responseNormally
      }
      case _: HttpRequest => {
        channel.writeResponse { new HttpResponse(400) }
        ResponseAction.responseNormally
      }
    }

  }
  val factory = new HttpChannelHandlerFactory(handler, 8)

  val listening_channel_configurator: ServerSocketChannelWrapper => Unit = wrapper => {
    wrapper.setOption[java.lang.Boolean](java.net.StandardSocketOptions.SO_REUSEADDR, true)
    wrapper.setBacklog(1024 * 8)
  }

  //val server = new NioSocketServer("127.0.0.1", 8181, factory, listening_socket_options = List(reuse_addr))

  val threadFactory = new java.util.concurrent.ThreadFactory() {
    def newThread(r: Runnable) = {
      new Thread(r)
    }
  }
  val mt = new MultipleThreadHandlerFactory(1, threadFactory, Integer.MAX_VALUE, factory)
  //val server = new NioSocketServer1("127.0.0.1", 8181, mt, listening_channel_configurator = listening_channel_configurator)
  //val server = new NioSocketServer1("127.0.0.1", 8181, factory, listening_channel_configurator = listening_channel_configurator)
  //val server = new NioSocketAcceptor("127.0.0.1", 8181, 2, mt, listening_channel_configurator = listening_channel_configurator)
  val server = new NioSocketAcceptor("127.0.0.1", 8181, 2, factory, listening_channel_configurator = listening_channel_configurator)

  server.start(false)

}