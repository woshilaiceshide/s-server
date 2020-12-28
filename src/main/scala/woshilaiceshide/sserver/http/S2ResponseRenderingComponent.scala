package woshilaiceshide.sserver.http

import spray.util._
import spray.http._
import spray.http.HttpHeaders._
import spray.can.rendering._

import scala.annotation._

import RenderSupport._
import HttpProtocols._

case class S2ResponsePartRenderingContext(
  responsePart: HttpResponsePart,
  requestMethod: HttpMethod = HttpMethods.GET,
  requestProtocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
  closeAfterResponseCompletion: Boolean = false)

object S2ResponseRenderingComponent {

  sealed trait CloseMode {
    def shouldCloseNow(part: HttpResponsePart, closeAfterEnd: Boolean): Boolean
  }
  object CloseMode {
    case object DontClose extends CloseMode {
      def shouldCloseNow(part: HttpResponsePart, closeAfterEnd: Boolean): Boolean =
        closeAfterEnd && part.isInstanceOf[HttpMessageEnd]
    }
    case object CloseNow extends CloseMode {
      def shouldCloseNow(part: HttpResponsePart, closeAfterEnd: Boolean): Boolean = true
    }
    case object CloseAfterEnd extends CloseMode {
      def shouldCloseNow(part: HttpResponsePart, closeAfterEnd: Boolean): Boolean =
        part.isInstanceOf[HttpMessageEnd]
    }

    def closeNowIf(doClose: Boolean): CloseMode = if (doClose) CloseNow else DontClose
  }

}

trait S2ResponseRenderingComponent {

  import S2ResponseRenderingComponent._

  def configurator: HttpConfigurator

  //def serverHeaderValue: String
  //def chunklessStreaming: Boolean
  //def transparentHeadRequests: Boolean

  def serverHeaderValue: String = configurator.server_name
  def chunklessStreaming: Boolean = configurator.chunkless_streaming
  def transparentHeadRequests: Boolean = configurator.transparent_header_requests

  private def suppressionWarning(log: akka.event.LoggingAdapter, h: HttpHeader): Unit =
    suppressionWarning(log, h, "this header is set internally and automatically!")

  private def suppressionWarning(log: akka.event.LoggingAdapter, h: HttpHeader, msg: String = "this header is set internally and automatically!"): Unit =
    log.warning("Explicitly set response header '{}' is ignored, {}", h, msg)

  // returns a boolean indicating whether the connection is to be closed after this response was sent
  def renderResponsePartRenderingContext(
    r: RichBytesRendering,
    ctx: S2ResponsePartRenderingContext,
    log: akka.event.LoggingAdapter,
    write_server_and_date_headers: Boolean): CloseMode = {

    def renderResponseStart(response: HttpResponse, allowUserContentType: Boolean,
      contentLengthDefined: Boolean): Boolean = {

      def render(h: HttpHeader) = r ~~ h ~~ CrLf

      def shouldClose(contentLengthDefined: Boolean, connectionHeader: Connection) =
        ctx.closeAfterResponseCompletion || // request wants to close
          (connectionHeader != null && connectionHeader.hasClose) || // application wants to close
          (chunkless && !contentLengthDefined) // missing content-length, close needed as data boundary

      def renderContentType(x: `Content-Type`, userContentType: Boolean): Boolean = {
        if (userContentType) { suppressionWarning(log, x, "another `Content-Type` header was already rendered"); true }
        else if (!allowUserContentType) { suppressionWarning(log, x, "the response Content-Type is set via the response's HttpEntity!"); false }
        else {
          //optimization
          //render(x);
          if (x.contentType eq ContentTypes.`text/plain(UTF-8)`) {
            r ~~ `Content-Type--text/plain(UTF-8)-Bytes`
          } else if (x.contentType eq ContentTypes.`text/plain`) {
            r ~~ `Content-Type--text/plain-Bytes`
          } else {
            x.renderValue(r ~~ `Content-Type-Bytes`)
          }
          true
        }

      }

      def renderConnection(old_header: Connection, current_header: `Connection`) = {
        val new_header = if (old_header eq null) current_header else Connection(current_header.tokens ++ old_header.tokens)
        if (current_header.hasUpgrade) render(current_header)
        new_header
      }

      def isThisRawHeaderIllegal(x: HttpHeader) = {
        x.lowercaseName == "content-type" ||
          x.lowercaseName == "content-length" ||
          x.lowercaseName == "transfer-encoding" ||
          x.lowercaseName == "date" ||
          x.lowercaseName == "server" ||
          x.lowercaseName == "connection"
      }

      def renderGeneralHeader(x: HttpHeader) = {
        if (x.isInstanceOf[RawHeader] && isThisRawHeaderIllegal(x)) {
          suppressionWarning(log, x, "illegal RawHeader")
        } else {
          render(x)
        }
      }

      def renderEntity(userContentType: Boolean, connHeader: Connection) = {
        response.entity match {
          case HttpEntity.NonEmpty(ContentTypes.NoContentType, _) ⇒
          case HttpEntity.NonEmpty(contentType, _) if !userContentType ⇒ {
            if (contentType eq ContentTypes.`text/plain(UTF-8)`) {
              r ~~ `Content-Type--text/plain(UTF-8)-CrLf-Bytes`
            } else if (contentType eq ContentTypes.`text/plain`) {
              r ~~ `Content-Type--text/plain-CrLf-Bytes`
            } else {
              r ~~ `Content-Type` ~~ contentType ~~ CrLf
            }
          }
          case _ ⇒
        }
        shouldClose(contentLengthDefined, connHeader)
      }

      @tailrec def renderHeaders(remaining: List[HttpHeader], contentLengthDefined: Boolean, userContentType: Boolean = false, connHeader: Connection = null): Boolean = {
        remaining match {
          case head :: tail ⇒
            head match {
              case x: `Content-Type` ⇒
                val userCT = renderContentType(x, userContentType)
                renderHeaders(tail, contentLengthDefined, userContentType = userCT, connHeader)

              case x: `Content-Length` ⇒
                if (contentLengthDefined) suppressionWarning(log, x, "another `Content-Length` header was already rendered")
                else {
                  //render(x)
                  configurator.render_content_length(r, x.length, false)
                }
                renderHeaders(tail, true, userContentType, connHeader)

              case `Transfer-Encoding`(_) | Date(_) | Server(_) ⇒
                suppressionWarning(log, head)
                renderHeaders(tail, contentLengthDefined, userContentType, connHeader)

              case x: `Connection` ⇒
                val newHeader = renderConnection(connHeader, x)
                renderHeaders(tail, contentLengthDefined, userContentType, newHeader)

              case x ⇒
                renderGeneralHeader(x)
                renderHeaders(tail, contentLengthDefined, userContentType, connHeader)
            }
          case _ ⇒ renderEntity(userContentType, connHeader)
        }
      }

      import response._
      //status is rendered when retrieving the render.
      //this optimization is ugly.
      //if (status eq StatusCodes.OK) r ~~ DefaultStatusLine else r ~~ StatusLineStart ~~ status ~~ CrLf

      if (write_server_and_date_headers) {
        configurator.render_server_and_date_header(r)
      }
      renderHeaders(headers, contentLengthDefined)
    }

    def renderResponse(response: HttpResponse): Boolean = {

      import response._

      val close = renderResponseStart(response,
        allowUserContentType = entity.isEmpty && ctx.requestMethod == HttpMethods.HEAD,
        contentLengthDefined = ctx.requestMethod != HttpMethods.HEAD || transparentHeadRequests)

      //TODO render all the general headers in one time:
      /**
       * < HTTP/1.1 200 OK
       * < Server: S-SERVER/3.1
       * < Connection: Keep-Alive
       * < Content-Length: 13
       * < Content-Type: text/plain
       * < Date: Mon, 28 Dec 2020 06:58:03 GMT
       */
      renderConnectionHeader(close)

      // don't set a Content-Length header for non-keep-alive HTTP/1.0 responses (rely on body end by connection close),
      // however, for non-transparent HEAD requests let the user manage the header
      /*
      if ((response.protocol == `HTTP/1.1` || !close) && (ctx.requestMethod != HttpMethods.HEAD || transparentHeadRequests))
        r ~~ `Content-Length` ~~ entity.data.length ~~ CrLf
      r ~~ CrLf
      */
      if ((response.protocol == `HTTP/1.1` || !close) && (ctx.requestMethod != HttpMethods.HEAD || transparentHeadRequests)) {
        //r ~~ `Content-Length` ~~ entity.data.length ~~ TwoCrLf
        configurator.render_content_length(r, entity.data.length, true)
      } else {
        r ~~ CrLf
      }
      if (entity.nonEmpty && ctx.requestMethod != HttpMethods.HEAD) r ~~ entity.data
      close
    }

    def renderChunkedResponseStart(response: HttpResponse): CloseMode = {
      val close = renderResponseStart(response,
        allowUserContentType = response.entity.isEmpty,
        contentLengthDefined = false)
      renderConnectionHeader(close)

      /*
      if (!chunkless) r ~~ `Transfer-Encoding` ~~ Chunked ~~ CrLf
      r ~~ CrLf
      */
      if (!chunkless) {
        r ~~ `Transfer-Encoding: Chunked-TwoCrLf`
      } else {
        r ~~ CrLf
      }

      if (ctx.requestMethod != HttpMethods.HEAD)
        response.entity match {
          case HttpEntity.Empty ⇒ // nothing to do
          case HttpEntity.NonEmpty(_, data) ⇒ if (chunkless) r ~~ data else r ~~ MessageChunk(data)
        }
      if (close) CloseMode.CloseAfterEnd else CloseMode.DontClose
    }

    def renderConnectionHeader(close: Boolean): Unit =
      ctx.requestProtocol match {
        case `HTTP/1.0` if !close ⇒ r ~~ `Connection: KeepAlive-CrLf` //Connection ~~ KeepAlive ~~ CrLf
        case `HTTP/1.1` if close ⇒ r ~~ `Connection: Close-CrLf` //Connection ~~ Close ~~ CrLf
        case _ ⇒ // no need for rendering
      }

    def chunkless = chunklessStreaming || (ctx.requestProtocol eq `HTTP/1.0`)

    ctx.responsePart match {
      case x: HttpResponse ⇒ CloseMode.closeNowIf(renderResponse(x))
      case x: ChunkedResponseStart ⇒ renderChunkedResponseStart(x.response)
      case x: MessageChunk ⇒
        if (ctx.requestMethod != HttpMethods.HEAD)
          if (chunkless) r ~~ x.data else r ~~ x
        CloseMode.DontClose
      case x: ChunkedMessageEnd ⇒
        if (ctx.requestMethod != HttpMethods.HEAD && !chunkless) r ~~ x
        CloseMode.closeNowIf(ctx.closeAfterResponseCompletion)
    }
  }

}