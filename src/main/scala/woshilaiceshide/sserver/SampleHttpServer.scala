package woshilaiceshide.sserver

import woshilaiceshide.sserver.httpd._
import WebSocket13.WSText
import spray.can._
import spray.http._
import spray.http.HttpEntity.apply
import spray.http.StatusCode.int2StatusCode
import woshilaiceshide.sserver.httpd.WebSocket13
import woshilaiceshide.sserver.nio.NioSocketServer

object SampleHttpServer extends App {

  val handler = new PlainHttpChannelHandler {

    def channelClosed(channel: HttpChannelWrapper): Unit = {}

    def requestReceived(request: HttpRequestPart, channel: HttpChannelWrapper): HttpRequestProcessor = request match {
      case HttpRequest(HttpMethods.GET, Uri.Path("/ping"), _, _, _) =>
        channel.respond {
          new HttpResponse(200, "pong")
        }
      case x @ HttpRequest(HttpMethods.GET, Uri.Path("/ws_example"), _, _, _) =>
        channel.toWebSocketChannelHandler(x, Nil, 1024, c => {
          new WebSocketChannelHandler() {

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
      case _ => {
        //I DOES NOT support chunk request.
        null
      }
    }
  }
  val factory = new HttpChannelHandlerFactory(handler)

  val server = new NioSocketServer("127.0.0.1", 8181, factory)
  server.start(false)

}