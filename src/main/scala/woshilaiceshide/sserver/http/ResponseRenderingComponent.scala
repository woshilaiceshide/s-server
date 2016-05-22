package woshilaiceshide.sserver.http

import spray.util._

import spray.http._
import spray.http.HttpHeaders._

import scala.annotation._

private[http] object RenderSupport {
  //HTTP/1.0?
  val DefaultStatusLine = "HTTP/1.1 200 OK\r\n".getAsciiBytes
  val StatusLineStart = "HTTP/1.1 ".getAsciiBytes
  val Chunked = "chunked".getAsciiBytes
  val KeepAlive = "Keep-Alive".getAsciiBytes
  val Close = "close".getAsciiBytes
  val Upgrade = "Upgrade".getAsciiBytes

  def getBytes(renderable: Renderable, sizeHint: Int = 512) = {
    val r = new ByteArrayRendering(sizeHint)
    r ~~ renderable
    r.get
  }

  def getBytes(l: Long) = {
    val r = new ByteArrayRendering(64)
    r ~~ l
    r.get
  }

  val CrLf = getBytes(Rendering.CrLf)
  val TwoCrLf = getBytes(Rendering.CrLf) ++ getBytes(Rendering.CrLf)

  val `Content-Type-Bytes` = "Content-Type: ".getAsciiBytes
  //TODO for more common content types
  val `Content-Type--text/plain(UTF-8)-Bytes` = getBytes(`Content-Type`(ContentTypes.`text/plain(UTF-8)`))
  val `Content-Type--text/plain(UTF-8)-CrLf-Bytes` = getBytes(`Content-Type`(ContentTypes.`text/plain(UTF-8)`)) ++ CrLf
  val `Content-Type--text/plain-Bytes` = getBytes(`Content-Type`(ContentTypes.`text/plain`))
  val `Content-Type--text/plain-CrLf-Bytes` = getBytes(`Content-Type`(ContentTypes.`text/plain`)) ++ CrLf
  val `Content-Length-Bytes` = "Content-Length: ".getAsciiBytes

  val `Connection: KeepAlive-CrLf` = getBytes(Connection) ++ ": ".getAsciiBytes ++ KeepAlive ++ CrLf
  val `Connection: Close-CrLf` = getBytes(Connection) ++ ": ".getAsciiBytes ++ Close ++ CrLf

  val `Transfer-Encoding: Chunked-TwoCrLf` = getBytes(`Transfer-Encoding`) ++ ": ".getAsciiBytes ++ Chunked ++ TwoCrLf

  implicit object MessageChunkRenderer extends Renderer[MessageChunk] {
    def render[R <: Rendering](r: R, chunk: MessageChunk): r.type = {
      import chunk._
      r ~~% data.length
      if (!extension.isEmpty) r ~~ ';' ~~ extension
      r ~~ CrLf ~~ data ~~ CrLf
    }
  }

  implicit object ChunkedMessageEndRenderer extends Renderer[ChunkedMessageEnd] {
    implicit val trailerRenderer = Renderer.genericSeqRenderer[Renderable, HttpHeader](Rendering.CrLf, Rendering.Empty)
    def render[R <: Rendering](r: R, part: ChunkedMessageEnd): r.type = {
      r ~~ '0'
      if (!part.extension.isEmpty) r ~~ ';' ~~ part.extension
      r ~~ CrLf
      if (!part.trailer.isEmpty) r ~~ part.trailer ~~ CrLf
      r ~~ CrLf
    }
  }
}

import RenderSupport._
import HttpProtocols._

case class ResponsePartRenderingContext(
  responsePart: HttpResponsePart,
  requestMethod: HttpMethod = HttpMethods.GET,
  requestProtocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
  closeAfterResponseCompletion: Boolean = false)

object ResponseRenderingComponent {

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

trait ResponseRenderingComponent {

  import ResponseRenderingComponent._

  def configurator: HttpConfigurator

  //def serverHeaderValue: String
  //def chunklessStreaming: Boolean
  //def transparentHeadRequests: Boolean

  def serverHeaderValue: String = configurator.server_name
  def chunklessStreaming: Boolean = configurator.chunkless_streaming
  def transparentHeadRequests: Boolean = configurator.transparent_header_requestes

  //TODO ???
  private[this] def serverHeaderPlusDateColonSP =
    serverHeaderValue match {
      case "" ⇒ "Date: ".getAsciiBytes
      case x ⇒ ("Server: " + x + "\r\nDate: ").getAsciiBytes
    }

  // returns a boolean indicating whether the connection is to be closed after this response was sent
  def renderResponsePartRenderingContext(r: Rendering, ctx: ResponsePartRenderingContext,
    log: akka.event.LoggingAdapter,
    writeServerAndDateHeader: Boolean): CloseMode = {

    def renderResponseStart(response: HttpResponse, allowUserContentType: Boolean,
      contentLengthDefined: Boolean): Boolean = {

      def render(h: HttpHeader) = r ~~ h ~~ CrLf

      def suppressionWarning(h: HttpHeader, msg: String = "the spray-can HTTP layer sets this header automatically!"): Unit =
        log.warning("Explicitly set response header '{}' is ignored, {}", h, msg)

      def shouldClose(contentLengthDefined: Boolean, connectionHeader: Connection) =
        ctx.closeAfterResponseCompletion || // request wants to close
          (connectionHeader != null && OptimizedUtility.hasClose(connectionHeader)) || // application wants to close
          (chunkless && !contentLengthDefined) // missing content-length, close needed as data boundary

      @tailrec def renderHeaders(remaining: List[HttpHeader], contentLengthDefined: Boolean,
        userContentType: Boolean = false, connHeader: Connection = null): Boolean = {
        remaining match {
          //case head :: tail ⇒ head match {
          case _ if remaining.length > 0 ⇒
            val head = remaining.head
            val tail = remaining.tail
            head match {
              case x: `Content-Type` ⇒
                val userCT =
                  if (userContentType) { suppressionWarning(x, "another `Content-Type` header was already rendered"); true }
                  else if (!allowUserContentType) { suppressionWarning(x, "the response Content-Type is set via the response's HttpEntity!"); false }
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
                renderHeaders(tail, contentLengthDefined, userContentType = userCT, connHeader)

              case x: `Content-Length` ⇒
                if (contentLengthDefined) suppressionWarning(x, "another `Content-Length` header was already rendered")
                else {
                  //render(x)
                  configurator.render_content_length(r, x.length, false)
                }
                renderHeaders(tail, contentLengthDefined = true, userContentType, connHeader)

              case `Transfer-Encoding`(_) | Date(_) | Server(_) ⇒
                suppressionWarning(head)
                renderHeaders(tail, contentLengthDefined, userContentType, connHeader)

              case x: `Connection` ⇒
                val connectionHeader = if (connHeader eq null) x else Connection(x.tokens ++ connHeader.tokens)
                if (OptimizedUtility.hasUpgrade(x)) render(x)
                renderHeaders(tail, contentLengthDefined, userContentType, connectionHeader)

              case x: RawHeader if x.lowercaseName == "content-type" ||
                x.lowercaseName == "content-length" ||
                x.lowercaseName == "transfer-encoding" ||
                x.lowercaseName == "date" ||
                x.lowercaseName == "server" ||
                x.lowercaseName == "connection" ⇒
                suppressionWarning(x, "illegal RawHeader")
                renderHeaders(tail, contentLengthDefined, userContentType, connHeader)

              case x ⇒
                render(x)
                renderHeaders(tail, contentLengthDefined, userContentType, connHeader)
            }

          //case Nil ⇒
          case _ ⇒
            response.entity match {
              //case HttpEntity.NonEmpty(ContentTypes.NoContentType, _) ⇒
              //!!!
              case HttpEntity.NonEmpty(contentType, _) if ContentTypes.NoContentType eq contentType ⇒
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
      }

      import response._
      //status is rendered when retrieving the render.
      //this optimization is ugly.
      //if (status eq StatusCodes.OK) r ~~ DefaultStatusLine else r ~~ StatusLineStart ~~ status ~~ CrLf
      //TODO many optimizations can be done here: 
      //1. use 'ThreadLocal'
      //2. cache the last second's bytes
      if (writeServerAndDateHeader) {
        r ~~ serverAndDateHeader
      }
      renderHeaders(headers, contentLengthDefined)
    }

    def renderResponse(response: HttpResponse): Boolean = {

      import response._

      val close = renderResponseStart(response,
        allowUserContentType = entity.isEmpty && ctx.requestMethod == HttpMethods.HEAD,
        contentLengthDefined = ctx.requestMethod != HttpMethods.HEAD || transparentHeadRequests)

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

  // for max perf we cache the ServerAndDateHeader of the last second here
  @volatile private[this] var cachedServerAndDateHeader: (Long, Array[Byte]) = _

  private def serverAndDateHeader: Array[Byte] = {
    var (cachedSeconds, cachedBytes) = if (cachedServerAndDateHeader != null) cachedServerAndDateHeader else (0L, null)
    val now = System.currentTimeMillis
    if (now / 1000 != cachedSeconds) {
      cachedSeconds = now / 1000
      val r = new ByteArrayRendering(serverHeaderPlusDateColonSP.length + 31)
      dateTime(now).renderRfc1123DateTimeString(r ~~ serverHeaderPlusDateColonSP) ~~ CrLf
      cachedBytes = r.get
      cachedServerAndDateHeader = cachedSeconds -> cachedBytes
    }
    cachedBytes
  }

  protected def dateTime(now: Long) = DateTime(now) // split out so we can stabilize by overriding in tests

}