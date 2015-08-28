package woshilaiceshide.sserver

import woshilaiceshide.sserver.httpd._
import WebSocket13.WSText
import spray.can._
import spray.http._
import spray.http.HttpEntity.apply
import spray.http.StatusCode.int2StatusCode
import woshilaiceshide.sserver.httpd.WebSocket13
import woshilaiceshide.sserver.nio.NioSocketServer
import spray.can.WebSocketChannelHandler
import spray.can.PlainHttpChannelHandler
import spray.can.HttpRequestProcessor
import spray.can.HttpChannelWrapper
import spray.can.HttpChannelHandlerFactory

object SampleHttpServer extends App {

  val handler = new PlainHttpChannelHandler {

    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent._

    def channelClosed(channel: HttpChannelWrapper): Unit = {

    }

    def requestReceived(request: HttpRequestPart, channel: HttpChannelWrapper): HttpRequestProcessor = request match {
      case HttpRequest(HttpMethods.GET, Uri.Path("/ping1"), _, _, _) =>
        channel.respond {
          new HttpResponse(200, "pong1\r\n")
        }
      case HttpRequest(HttpMethods.GET, Uri.Path("/ping0"), _, _, _) =>
        channel.respondAsynchronously {
          Future { Thread.sleep(3 * 1000); new HttpResponse(200, "pong0\r\n") }
        }
      case x @ HttpRequest(HttpMethods.GET, Uri.Path("/demo"), _, _, _) =>
        channel.toWebSocketChannelHandler(x, Nil, 1024, c => {
          new WebSocketChannelHandler() {

            def inputEnded() = {
              c.close(WebSocket13.CloseCode.NORMAL_CLOSURE_OPTION)
            }

            def becomeWritable() {

            }
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
          }
        })
      case _: HttpRequest => {
        channel.respond { new HttpResponse(400) }
      }
      case _ => {
        //I DOES NOT support chunked request.
        null
      }
    }
  }
  val factory = new HttpChannelHandlerFactory(handler, 8)

  val server = new NioSocketServer("127.0.0.1", 8181, factory)
  server.start(false)

}