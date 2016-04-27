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

  val handler = new PlainHttpChannelHandler {

    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent._

    def channelClosed(channel: HttpChannelWrapper): Unit = {
      //TODO
    }

    def requestReceived(request: HttpRequest, channel: HttpChannelWrapper, classifier: RequestClassifier): ResponseAction = request match {
      case HttpRequest(HttpMethods.GET, Uri.Path("/ping1"), _, _, _) => {
        channel.writeResponse {
          new HttpResponse(200, "pong1\r\n")
        }
        ResponseAction.normal
      }
      case HttpRequest(HttpMethods.GET, Uri.Path("/ping0"), _, _, _) => {
        Future {
          Thread.sleep(3 * 1000);
          channel.writeResponse(new HttpResponse(200, "pong0\r\n"))
        }
        ResponseAction.normal
      }
      case x @ HttpRequest(HttpMethods.GET, Uri.Path("/demo"), _, _, _) => {
        //TODO optimized, a single instance is enough.
        val h = (c: WebSocketChannelWrapper) => {

          val handler = new WebSocketChannelHandler() {

            override def idled(): Unit = {}

            def pongReceived(frame: WebSocket13.WSFrame): Unit = {
              println("ping ok")
            }

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
            def fireClosed(code: WebSocket13.CloseCode.Value, reason: String): Unit = {}

            def inputEnded(): Unit = {
              c.close(WebSocket13.CloseCode.NORMAL_CLOSURE_OPTION)
            }

            def channelWritable(): Unit = {}
          }

          (handler, WebSocket13.default_parser(2048))
        }

        ResponseAction.acceptWebsocket { Some(h) }
      }
      case _: HttpRequest if classifier.classification == RequestClassification.ChunkedHttpStart => {
        channel.writeResponse { new HttpResponse(400, "I DOES NOT support chunked request.") }
        ResponseAction.normal
      }
      case _: HttpRequest => {
        channel.writeResponse { new HttpResponse(400) }
        ResponseAction.normal
      }
    }

    //TODO
    def channelWritable(channel: HttpChannelWrapper): Unit = {}

  }
  val factory = new HttpChannelHandlerFactory(handler, 8)

  val server = new NioSocketServer("127.0.0.1", 8181, factory)
  server.start(false)

}