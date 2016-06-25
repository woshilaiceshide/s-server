package woshilaiceshide.sserver.http

import java.lang.{ StringBuilder ⇒ JStringBuilder }
import scala.annotation.tailrec
import akka.util.ByteString
import spray.can.parsing._
import spray.http._
import spray.http.parser.ParserInput
import HttpMethods._
import StatusCodes._
import HttpHeaders._
import spray.util.CharUtils

class S2HttpRequestPartParser(settings: spray.can.parsing.ParserSettings, rawRequestUriHeader: Boolean = false)(headerParser: HttpHeaderParser)
    extends S2HttpMessagePartParser(settings, headerParser) {

  private[this] var method: HttpMethod = _ //GET
  private[this] var uri: Uri = _ //Uri.Empty
  private[this] var uriBytes: Array[Byte] = _ //Array.emptyByteArray

  def copyWith(warnOnIllegalHeader: ErrorInfo ⇒ Unit): S2HttpRequestPartParser =
    new S2HttpRequestPartParser(settings, rawRequestUriHeader)(headerParser.copyWith(warnOnIllegalHeader))

  def parseMessage(input: ByteString, offset: Int): Result = {
    var cursor = parseMethod(input, offset)
    cursor = parseRequestTarget(input, cursor)
    cursor = parseProtocol(input, cursor)
    //if (byteChar(input, cursor) == '\r' && byteChar(input, cursor + 1) == '\n')
    //optimization
    if (validateNextTwoChars(input, cursor, '\r', '\n'))
      parseHeaderLines(input, cursor + 2)
    else badProtocol
  }

  def parseMethod(input: ByteString, cursor: Int): Int = {
    @tailrec def parseCustomMethod(ix: Int = 0, sb: JStringBuilder = new JStringBuilder(16)): Int =
      if (ix < 16) { // hard-coded maximum custom method length
        byteChar(input, cursor + ix) match {
          case ' ' ⇒
            HttpMethods.getForKey(sb.toString) match {
              case Some(m) ⇒ { method = m; cursor + ix + 1 }
              case None ⇒ parseCustomMethod(Int.MaxValue, sb)
            }
          case c ⇒ parseCustomMethod(ix + 1, sb.append(c))
        }
      } else throw new ParsingException(NotImplemented, ErrorInfo("Unsupported HTTP method", sb.toString))

    @tailrec def parseMethod(meth: HttpMethod, ix: Int = 1): Int =
      if (ix == meth.value.length)
        if (byteChar(input, cursor + ix) == ' ') {
          method = meth
          cursor + ix + 1
        } else parseCustomMethod()
      else if (byteChar(input, cursor + ix) == meth.value.charAt(ix)) parseMethod(meth, ix + 1)
      else parseCustomMethod()

    byteChar(input, cursor) match {
      case 'G' ⇒ parseMethod(GET)
      case 'P' ⇒ byteChar(input, cursor + 1) match {
        case 'O' ⇒ parseMethod(POST, 2)
        case 'U' ⇒ parseMethod(PUT, 2)
        case 'A' ⇒ parseMethod(PATCH, 2)
        case _ ⇒ parseCustomMethod()
      }
      case 'D' ⇒ parseMethod(DELETE)
      case 'H' ⇒ parseMethod(HEAD)
      case 'O' ⇒ parseMethod(OPTIONS)
      case 'T' ⇒ parseMethod(TRACE)
      case 'C' ⇒ parseMethod(CONNECT)
      case _ ⇒ parseCustomMethod()
    }
  }

  def parseRequestTarget(input: ByteString, cursor: Int): Int = {
    val uriStart = cursor
    val uriEndLimit = cursor + settings.maxUriLength

    @tailrec def findUriEnd(ix: Int = cursor): Int =
      if (ix == input.length) throw NotEnoughDataException
      else if (CharUtils.isWhitespaceOrNewline(input(ix).toChar)) ix
      else if (ix < uriEndLimit) findUriEnd(ix + 1)
      else throw new ParsingException(RequestUriTooLong,
        s"URI length exceeds the configured limit of ${settings.maxUriLength} characters")

    val uriEnd = findUriEnd()
    try {
      uriBytes = input.iterator.slice(uriStart, uriEnd).toArray[Byte]
      uri = Uri.parseHttpRequestTarget(uriBytes, mode = settings.uriParsingMode)
    } catch {
      case e: IllegalUriException ⇒ throw new ParsingException(BadRequest, e.info)
    }
    uriEnd + 1
  }

  def badProtocol = throw new ParsingException(HTTPVersionNotSupported)

  private def parseChunkedEntity(headers: List[HttpHeader], input: ByteString, bodyStart: Int, clh: `Content-Length`, cth: `Content-Type`, closeAfterResponseCompletion: Boolean) = {
    if (clh == null) {
      copy(input, bodyStart)
      emitLazily(chunkStartMessage(headers), closeAfterResponseCompletion) {
        parseChunk(remain, 0, closeAfterResponseCompletion)
      }
    } else fail("A chunked request must not contain a Content-Length header.")
  }

  private def parseNonChunkedEntity(headers: List[HttpHeader], input: ByteString, bodyStart: Int, clh: `Content-Length`, cth: `Content-Type`, closeAfterResponseCompletion: Boolean) = {
    val contentLength = if (null == clh) {
      0
    } else {
      clh.length
    }
    if (contentLength == 0) {
      if (input.length > bodyStart) {
        copy(input, bodyStart)
        emitLazily(message(headers, HttpEntity.Empty), closeAfterResponseCompletion) { parseMessageSafe(remain) }
      } else {
        emitDirectly(message(headers, HttpEntity.Empty), closeAfterResponseCompletion) { reset() }
      }
    } else if (contentLength <= settings.maxContentLength) {
      parseFixedLengthBody(headers, input, bodyStart, contentLength, cth, closeAfterResponseCompletion)

    } else {
      fail(RequestEntityTooLarge, s"Request Content-Length $contentLength exceeds the configured limit of ${settings.maxContentLength}")
    }
  }

  // http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-22#section-3.3
  def parseEntity(headers: List[HttpHeader], input: ByteString, bodyStart: Int, clh: `Content-Length`, cth: `Content-Type`, teh: `Transfer-Encoding`, hostHeaderPresent: Boolean, closeAfterResponseCompletion: Boolean): Result = {

    def parseEntity1() = {
      teh match {
        case null | `Transfer-Encoding`(Seq("identity")) ⇒
          parseNonChunkedEntity(headers, input, bodyStart, clh, cth, closeAfterResponseCompletion)
        case `Transfer-Encoding`(Seq("chunked")) ⇒
          parseChunkedEntity(headers, input, bodyStart, clh, cth, closeAfterResponseCompletion)
        case te ⇒ fail(NotImplemented, s"$te is not supported by this server")
      }
    }

    if (hostHeaderPresent || protocol == HttpProtocols.`HTTP/1.0`) {
      parseEntity1()
    } else {
      fail("Request is missing required `Host` header")
    }
  }

  def message(headers: List[HttpHeader], entity: HttpEntity) = {
    val requestHeaders = if (!rawRequestUriHeader)
      headers
    else
      `Raw-Request-URI`(new String(uriBytes, HttpCharsets.`US-ASCII`.nioCharset)) :: headers

    HttpRequest(method, uri, requestHeaders, entity, protocol)
  }
  def chunkStartMessage(headers: List[HttpHeader]) = ChunkedRequestStart(message(headers, HttpEntity.Empty))
}