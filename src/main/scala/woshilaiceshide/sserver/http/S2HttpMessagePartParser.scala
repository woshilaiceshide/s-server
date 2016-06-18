package woshilaiceshide.sserver.http

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import akka.util.ByteString
import spray.can.parsing._
import spray.http._
import StatusCodes._
import HttpHeaders._
import HttpProtocols._
import spray.util.CharUtils

abstract class S2HttpMessagePartParser(val settings: spray.can.parsing.ParserSettings,
    val headerParser: HttpHeaderParser) extends Parser {
  protected var protocol: HttpProtocol = `HTTP/1.1`

  def apply(input: ByteString): Result = parseMessageSafe(input)

  def parseMessageSafe(input: ByteString, offset: Int = 0): Result = {
    def needMoreData = this.needMoreData(input, offset)(parseMessageSafe)
    if (input.length > offset)
      try parseMessage(input, offset)
      catch {
        case NotEnoughDataException ⇒ needMoreData
        case e: ParsingException ⇒ fail(e.status, e.info)
      }
    else {
      //needMoreData
      Result.NeedMoreData(this)
    }
  }

  def parseMessage(input: ByteString, offset: Int): Result

  def parseProtocol(input: ByteString, cursor: Int): Int = {
    @inline def byteCharDirectly(ix: Int) = input(cursor + ix).toChar
    if (input.length < cursor + 8) {
      throw NotEnoughDataException
    } else if (byteCharDirectly(0) == 'H' && byteCharDirectly(1) == 'T' && byteCharDirectly(2) == 'T' && byteCharDirectly(3) == 'P' &&
      byteCharDirectly(4) == '/' && byteCharDirectly(5) == '1' && byteCharDirectly(6) == '.') {
      protocol = byteCharDirectly(7) match {
        case '0' ⇒ `HTTP/1.0`
        case '1' ⇒ `HTTP/1.1`
        case _ ⇒ badProtocol
      }
      cursor + 8
    } else badProtocol
  }

  def badProtocol: Nothing

  def connectionCloseExpected(protocol: HttpProtocol, connectionHeader: Connection): Boolean = {

    if (protocol eq HttpProtocols.`HTTP/1.1`) {
      connectionHeader != null && connectionHeader.hasClose
    } else if (protocol eq HttpProtocols.`HTTP/1.0`) {
      connectionHeader == null || connectionHeader.hasNoKeepAlive
    } else {
      throw new Error("supposed to be not here.")
    }
  }

  @tailrec final def parseHeaderLines(
    input: ByteString,
    lineStart: Int,
    headers: ListBuffer[HttpHeader] = ListBuffer[HttpHeader](),
    headerCount: Int = 0,
    ch: Connection = null,
    clh: `Content-Length` = null,
    cth: `Content-Type` = null,
    teh: `Transfer-Encoding` = null,
    e100: Boolean = false,
    hh: Boolean = false): Result = {
    var lineEnd = 0
    val result: Result =
      try {
        lineEnd = headerParser.parseHeaderLine(input, lineStart)()
        null
      } catch {
        case NotEnoughDataException ⇒
          needMoreData(input, lineStart)(parseHeaderLinesAux(_, _, headers, headerCount, ch, clh, cth, teh, e100, hh))
        case e: ParsingException ⇒ fail(e.status, e.info)
      }
    if (result != null) result
    else headerParser.resultHeader match {
      case HttpHeaderParser.EmptyHeader ⇒
        val close = connectionCloseExpected(protocol, ch)
        val next = parseEntity(headers.toList, input, lineEnd, clh, cth, teh, hh, close)
        if (e100) Result.Expect100Continue(() ⇒ next) else next

      case h: Connection ⇒
        parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, h, clh, cth, teh, e100, hh)

      case h: `Content-Length` ⇒
        if (clh == null) parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, ch, h, cth, teh, e100, hh)
        else fail("HTTP message must not contain more than one Content-Length header")

      case h: `Content-Type` ⇒
        if (cth == null) parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, ch, clh, h, teh, e100, hh)
        else if (cth == h) parseHeaderLines(input, lineEnd, headers, headerCount, ch, clh, cth, teh, e100, hh)
        else fail("HTTP message must not contain more than one Content-Type header")

      case h: `Transfer-Encoding` ⇒
        parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, ch, clh, cth, h, e100, hh)

      case h: Expect ⇒
        parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, ch, clh, cth, teh, e100 = true, hh)

      case h if headerCount < settings.maxHeaderCount ⇒
        parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, ch, clh, cth, teh, e100, hh || h.isInstanceOf[Host])

      case _ ⇒ fail(s"HTTP message contains more than the configured limit of ${settings.maxHeaderCount} headers")
    }
  }

  // work-around for compiler bug complaining about non-tail-recursion if we inline this method
  def parseHeaderLinesAux(
    input: ByteString,
    lineStart: Int,
    headers: ListBuffer[HttpHeader],
    headerCount: Int,
    ch: Connection,
    clh: `Content-Length`,
    cth: `Content-Type`,
    teh: `Transfer-Encoding`,
    e100: Boolean,
    hh: Boolean): Result =
    parseHeaderLines(input, lineStart, headers, headerCount, ch, clh, cth, teh, e100, hh)

  def parseEntity(
    headers: List[HttpHeader],
    input: ByteString,
    bodyStart: Int,
    clh: `Content-Length`,
    cth: `Content-Type`,
    teh: `Transfer-Encoding`,
    hostHeaderPresent: Boolean,
    closeAfterResponseCompletion: Boolean): Result

  def parseFixedLengthBody(
    headers: List[HttpHeader],
    input: ByteString,
    bodyStart: Int,
    length: Long,
    cth: `Content-Type`,
    closeAfterResponseCompletion: Boolean): Result =
    if (length >= settings.autoChunkingThreshold) {
      val tmp = copy(input)
      emitLazily(chunkStartMessage(headers), closeAfterResponseCompletion) {
        parseBodyWithAutoChunking(tmp, bodyStart, length, closeAfterResponseCompletion)
      }
    } else if (length > Int.MaxValue) {
      fail("Content-Length > Int.MaxSize not supported for non-(auto)-chunked requests")
    } else if (bodyStart.toLong + length <= input.length) {
      val offset = bodyStart + length.toInt
      val msg = message(headers, entity(cth, input.slice(bodyStart, offset)))
      val tmp = copy(input)
      emitLazily(msg, closeAfterResponseCompletion) {
        if (tmp.isCompact) parseMessageSafe(tmp, offset)
        else parseMessageSafe(tmp.drop(offset))
      }
    } else needMoreData(input, bodyStart)(parseFixedLengthBody(headers, _, _, length, cth, closeAfterResponseCompletion))

  def parseChunk(input: ByteString, offset: Int, closeAfterResponseCompletion: Boolean): Result = {
    @tailrec def parseTrailer(extension: String, lineStart: Int, headers: List[HttpHeader] = Nil,
      headerCount: Int = 0): Result = {
      val lineEnd = headerParser.parseHeaderLine(input, lineStart)()
      headerParser.resultHeader match {
        case HttpHeaderParser.EmptyHeader ⇒
          val tmp = copy(input)
          emitLazily(ChunkedMessageEnd(extension, headers), closeAfterResponseCompletion) { parseMessageSafe(tmp, lineEnd) }
        case header if headerCount < settings.maxHeaderCount ⇒
          parseTrailer(extension, lineEnd, header :: headers, headerCount + 1)
        case _ ⇒ fail(s"Chunk trailer contains more than the configured limit of ${settings.maxHeaderCount} headers")
      }
    }

    def parseChunkBody(chunkSize: Int, extension: String, cursor: Int): Result =
      if (chunkSize > 0) {
        val chunkBodyEnd = cursor + chunkSize
        def result(terminatorLen: Int) = {
          val chunk = MessageChunk(HttpData(input.slice(cursor, chunkBodyEnd)), extension)
          val tmp = copy(input)
          emitLazily(chunk, closeAfterResponseCompletion) {
            parseChunk(tmp, chunkBodyEnd + terminatorLen, closeAfterResponseCompletion)
          }
        }
        byteChar(input, chunkBodyEnd) match {
          case '\r' if byteChar(input, chunkBodyEnd + 1) == '\n' ⇒ result(2)
          case '\n' ⇒ result(1)
          case x ⇒ fail("Illegal chunk termination")
        }
      } else parseTrailer(extension, cursor)

    @tailrec def parseChunkExtensions(chunkSize: Int, cursor: Int)(startIx: Int = cursor): Result =
      if (cursor - startIx <= settings.maxChunkExtLength) {
        def extension = CharUtils.asciiString(input, startIx, cursor)
        byteChar(input, cursor) match {
          case '\r' if byteChar(input, cursor + 1) == '\n' ⇒ parseChunkBody(chunkSize, extension, cursor + 2)
          case '\n' ⇒ parseChunkBody(chunkSize, extension, cursor + 1)
          case _ ⇒ parseChunkExtensions(chunkSize, cursor + 1)(startIx)
        }
      } else fail(s"HTTP chunk extension length exceeds configured limit of ${settings.maxChunkExtLength} characters")

    @tailrec def parseSize(cursor: Int = offset, size: Long = 0): Result =
      if (size <= settings.maxChunkSize) {
        byteChar(input, cursor) match {
          case c if CharUtils.isHexDigit(c) ⇒ parseSize(cursor + 1, size * 16 + CharUtils.hexValue(c))
          case ';' if cursor > offset ⇒ parseChunkExtensions(size.toInt, cursor + 1)()
          case '\r' if cursor > offset && byteChar(input, cursor + 1) == '\n' ⇒ parseChunkBody(size.toInt, "", cursor + 2)
          case c ⇒ fail(s"Illegal character '${CharUtils.escape(c)}' in chunk start")
        }
      } else fail(s"HTTP chunk size exceeds the configured limit of ${settings.maxChunkSize} bytes")

    try parseSize()
    catch {
      case NotEnoughDataException ⇒ needMoreData(input, offset)(parseChunk(_, _, closeAfterResponseCompletion))
      case e: ParsingException ⇒ fail(e.status, e.info)
    }
  }

  def parseBodyWithAutoChunking(input: ByteString, offset: Int, remainingBytes: Long,
    closeAfterResponseCompletion: Boolean): Result = {
    require(remainingBytes > 0)
    val chunkSize = math.min(remainingBytes, input.size - offset).toInt // safe conversion because input.size returns an Int
    if (chunkSize > 0) {
      val chunkEnd = offset + chunkSize
      val chunk = MessageChunk(HttpData(input.slice(offset, chunkEnd).compact))
      emitLazily(chunk, closeAfterResponseCompletion) {
        if (chunkSize == remainingBytes) { // last chunk
          val tmp = copy(input)
          emitLazily(ChunkedMessageEnd, closeAfterResponseCompletion) {
            if (tmp.isCompact) parseMessageSafe(tmp, chunkEnd)
            else parseMessageSafe(tmp.drop(chunkEnd))
          }
        } else {
          val tmp = copy(input)
          parseBodyWithAutoChunking(tmp, chunkEnd, remainingBytes - chunkSize, closeAfterResponseCompletion)
        }
      }
    } else needMoreData(input, offset)(parseBodyWithAutoChunking(_, _, remainingBytes, closeAfterResponseCompletion))
  }

  def entity(cth: `Content-Type`, body: ByteString): HttpEntity = {
    val contentType = if (null == cth) {
      ContentTypes.`application/octet-stream`
    } else {
      cth.contentType
    }
    HttpEntity(contentType, HttpData(body.compact))
  }

  private var copied = false
  protected final def copy(input: ByteString) = {
    if (!copied) {
      copied = true
      val bytes = input.toArray
      akka.spray.createByteStringUnsafe(bytes, 0, bytes.length)
    } else {
      input
    }
  }

  def needMoreData(input: ByteString, offset: Int)(next: (ByteString, Int) ⇒ Result): Result =
    if (offset == input.length) Result.NeedMoreData(next(_, 0))
    else {
      val tmp = copy(input)
      Result.NeedMoreData(more ⇒ next(tmp ++ more, offset))
    }

  def emitLazily(part: HttpMessagePart, closeAfterResponseCompletion: Boolean)(continue: ⇒ Result) =
    Result.EmitLazily(part, closeAfterResponseCompletion, () ⇒ continue)

  def emitDirectly(part: HttpMessagePart, closeAfterResponseCompletion: Boolean)(continue: Result) =
    Result.EmitDirectly(part, closeAfterResponseCompletion, continue)

  def fail(summary: String): Result = fail(summary, "")
  def fail(summary: String, detail: String): Result = fail(StatusCodes.BadRequest, summary, detail)
  def fail(status: StatusCode): Result = fail(status, status.defaultMessage)
  def fail(status: StatusCode, summary: String, detail: String = ""): Result = fail(status, ErrorInfo(summary, detail))
  def fail(status: StatusCode, info: ErrorInfo) = Result.ParsingError(status, info)

  def message(headers: List[HttpHeader], entity: HttpEntity): HttpMessagePart
  def chunkStartMessage(headers: List[HttpHeader]): HttpMessageStart
}