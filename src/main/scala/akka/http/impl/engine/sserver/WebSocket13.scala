package akka.http.impl.engine.sserver

import akka.util._

import woshilaiceshide.sserver.http.model._

import woshilaiceshide.sserver.nio._
import woshilaiceshide.sserver.utility._

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._

import java.security.MessageDigest
import java.io.UnsupportedEncodingException

/**
 * see https://en.wikipedia.org/wiki/WebSocket
 */
object WebSocket13 {

  val W_WEBSOCKET_KEY_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

  val WS_HEADER_UPGRADE = "Upgrade"
  val WS_HEADER_UPGRADE_VALUE = "websocket"

  private val WS_UPGRADE_HEADER = RawHeader(WS_HEADER_UPGRADE, WS_HEADER_UPGRADE_VALUE)

  val WS_HEADER_CONNECTION = "Connection"
  val WS_HEADER_CONNECTION_VALUE = "Upgrade"

  private val WS_CONNECTION_HEADER = Connection(WS_HEADER_CONNECTION_VALUE)

  val WS_HEADER_WEBSOCKET_VERSION = "Sec-WebSocket-Version"
  //NOW I support websocket 13 only.
  val WS_HEADER_WEBSOCKET_VERSION_13_VALUE = "13"
  val WS_HEADER_WEBSOCKET_KEY = "Sec-WebSocket-Key"
  val WS_HEADER_WEBSOCKET_ACCEPT = "Sec-WebSocket-Accept"
  val WS_HEADER_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol"

  sealed trait WebSocketAcceptance
  object WebSocketAcceptance {
    final case class Failed(response: woshilaiceshide.sserver.http.model.HttpResponse) extends WebSocketAcceptance
    final case class Ok(response: woshilaiceshide.sserver.http.model.HttpResponse) extends WebSocketAcceptance
  }

  def isAWebSocketRequest(request: woshilaiceshide.sserver.http.model.HttpRequest) = {
    2 == request.headers.filter { x =>
      (x.name == WS_HEADER_UPGRADE && x.value == WS_HEADER_UPGRADE_VALUE) ||
        {
          x match {
            case x: Connection => x.hasUpgrade
            case _ => false
          }
        }
    }.size
  }

  private def getAcceptedKey(key: String) = {
    val md = MessageDigest.getInstance("SHA-1")
    val raw = key + W_WEBSOCKET_KEY_MAGIC
    md.update(raw.getBytes(), 0, raw.length())
    val sha1 = md.digest()
    Base64.encode(sha1)
  }

  //is_already_seen_as_websocket: the caller has known this is a websocket request definitely. this is a small hint for optimization.
  def tryAccept(request: woshilaiceshide.sserver.http.model.HttpRequest, extraHeaders: List[HttpHeader] = Nil, cookies: List[HttpCookie]): WebSocketAcceptance = {
    if (!isAWebSocketRequest(request)) {
      WebSocketAcceptance.Failed(woshilaiceshide.sserver.http.model.HttpResponse(400, "not a websocket request", headers = extraHeaders ++ cookies.map { `Set-Cookie`(_) }))
    } else if (!request.headers.exists { x =>
      x.name == WS_HEADER_WEBSOCKET_VERSION &&
        x.value == WS_HEADER_WEBSOCKET_VERSION_13_VALUE
    }) {
      WebSocketAcceptance.Failed(woshilaiceshide.sserver.http.model.HttpResponse(400, s"${WS_HEADER_WEBSOCKET_VERSION} should be ${WS_HEADER_WEBSOCKET_VERSION_13_VALUE}, other versions are not supported."))
    } else {

      val key = request.headers.find { _.name == WS_HEADER_WEBSOCKET_KEY }.map(_.value)
      key match {
        case None => WebSocketAcceptance.Failed(woshilaiceshide.sserver.http.model.HttpResponse(400, s"where is the ${WS_HEADER_WEBSOCKET_KEY}?"))
        case Some(key1) => {
          try {
            val protocol = request.headers.find { _.name == WS_HEADER_WEBSOCKET_PROTOCOL }
            val acceptedKey = getAcceptedKey(key1)
            val headers = RawHeader(WS_HEADER_WEBSOCKET_ACCEPT, acceptedKey) ::
              WS_UPGRADE_HEADER ::
              //RawHeader(WS_HEADER_UPGRADE, WS_HEADER_UPGRADE_VALUE) ::
              WS_CONNECTION_HEADER ::
              //spray.http.HttpHeaders.Connection(WS_HEADER_CONNECTION_VALUE) ::
              //RawHeader(WS_HEADER_CONNECTION, WS_HEADER_CONNECTION_VALUE) ::
              extraHeaders
            val headers1 = protocol match {
              case None => headers
              case Some(p) => p :: headers
            }
            WebSocketAcceptance.Ok(woshilaiceshide.sserver.http.model.HttpResponse(status = StatusCodes.SwitchingProtocols, headers = headers1 ++ cookies.map { `Set-Cookie`(_) }))
          } catch {
            case _: java.security.NoSuchAlgorithmException => {
              //no sha1 on the server
              WebSocketAcceptance.Failed(woshilaiceshide.sserver.http.model.HttpResponse(500, "internal server error"))
            }
          }
        }
      }
    }
  }

  import scala.util.control.NoStackTrace
  object NoEnoughDataException extends RuntimeException with NoStackTrace
  //class ParsingException(msg: String) extends RuntimeException(msg)
  final case class WSParsingException(closeCode: CloseCode.Value) extends Exception //with scala.util.control.NoStackTrace

  sealed trait WSFrame { def op: OpCode.Value; def fin: Boolean; def bytes: Array[Byte]; def masked: Boolean; def mask_key: Array[Byte] }
  final case class WSText(text: String, fin: Boolean, bytes: Array[Byte], masked: Boolean, mask_key: Array[Byte]) extends WSFrame {
    def op = OpCode.TEXT
  }
  final case class WSBytes(bytes: Array[Byte], fin: Boolean, masked: Boolean, mask_key: Array[Byte]) extends WSFrame {
    def op = OpCode.BINARY
  }
  final case class WSClose(closeCode: CloseCode.Value, reason: String, bytes: Array[Byte], fin: Boolean, masked: Boolean, mask_key: Array[Byte]) extends WSFrame {
    def op = OpCode.CLOSE
  }
  final case class WSPing(bytes: Array[Byte], fin: Boolean, masked: Boolean, mask_key: Array[Byte]) extends WSFrame {
    def op = OpCode.PING
  }
  final case class WSPong(bytes: Array[Byte], fin: Boolean, masked: Boolean, mask_key: Array[Byte]) extends WSFrame {
    def op = OpCode.PONG
  }
  final case class WSContinuation(bytes: Array[Byte], fin: Boolean, masked: Boolean, mask_key: Array[Byte]) extends WSFrame {
    def op = OpCode.CONTINUATION
  }
  //case class WSRaw(op: OpCode.Value, bytes: Array[Byte], fin: Boolean, masked: Boolean, mask_key: Array[Byte]) extends WSFrame

  sealed trait WSResult
  object WSResult {
    final case class NeedMoreData(continue: WSFrameParser) extends WSResult
    final case class Emit(frame: WSFrame, continue: () => WSResult) extends WSResult
    case object End extends WSResult
    final case class Error(closeCode: CloseCode.Value, msg: String) extends WSResult
  }
  type WSFrameParser = ByteString => WSResult

  object OpCode extends scala.Enumeration {
    val CONTINUATION = Value(0)
    val TEXT = Value(1)
    val BINARY = Value(2)
    val CLOSE = Value(8)
    val PING = Value(9)
    val PONG = Value(10)

    def isControlCode(v: Value) = {
      v == CLOSE || v == PING || v == PONG
    }

  }

  //http://tools.ietf.org/html/rfc6455#section-7.4
  object CloseCode extends scala.Enumeration {
    val NORMAL_CLOSURE = Value(1000)
    val NORMAL_CLOSURE_OPTION = Some(NORMAL_CLOSURE)
    val GOING_AWAY = Value(1001)
    val PROTOCOL_ERROR = Value(1002)
    val CAN_NOT_ACCEPT_THE_TYPE_OF_DATA = Value(1003)
    val RESERVED = Value(1004)
    val NO_STATUS_CODE_IS_PRESENT = Value(1005)
    val CLOSED_ABNORMALLY = Value(1006)
    val BAD_PAYLOAD = Value(1007)
    val POLICY_VIOLATED = Value(1008)
    val MESSAGE_TOO_BIG = Value(1009)
    val SOME_EXTENSION_NOT_SUPPORTED_IN_THE_SERVER = Value(1010)
    val INTERNAL_SERVER_ERROR = Value(1011)
    val TLS_HANDSHAKE_FAILED = Value(1015)
  }

  def default_parser(max_payload_length: Int): WSFrameParser = parseSafe(_: ByteString, 0, max_payload_length)

  def needMoreData(input: ByteString, offset: Int)(next: (ByteString, Int) => WSResult): WSResult = {
    if (offset == input.length) WSResult.NeedMoreData(next(_, 0))
    else WSResult.NeedMoreData(more => next(input ++ more, offset))
  }

  @inline def why(s: String) = s match {
    case null | "" => "???"
    case x => x
  }
  def parseSafe(input: ByteString, offset: Int = 0, max_payload_length: Int = 512): WSResult = {
    def needMoreData = this.needMoreData(input, offset)(parseSafe(_, _, max_payload_length))
    if (input.length > offset)
      try parse(input, offset, max_payload_length)
      catch {
        case _: java.lang.ArrayIndexOutOfBoundsException => needMoreData
        case _: UnsupportedEncodingException => WSResult.Error(CloseCode.BAD_PAYLOAD, why(null))
        case NoEnoughDataException => needMoreData
        case ex: WSParsingException => WSResult.Error(ex.closeCode, why(ex.getMessage))
      }
    else needMoreData
  }
  val EMPTY_BYTE_ARRAY = new Array[Byte](0)
  def parse(input: ByteString, offset: Int, max_payload_length: Int): WSResult = {
    var cursor = offset
    @inline def nextByte() = { cursor = cursor + 1; input(cursor - 1) }
    //-1 or 0 ~ 255
    @inline def nextByteAsInt() = { cursor = cursor + 1; input(cursor - 1) & 0x00ff }
    @inline def nextBytes(count: Int) = if (0 == count) {
      EMPTY_BYTE_ARRAY
    } else {
      val tmp = input.slice(cursor, cursor + count).toArray
      if (tmp.length != count) {
        throw NoEnoughDataException
      }
      cursor = cursor + count
      tmp
    }
    val first = nextByte()

    val fin = (first & 0x80) != 0
    val op =
      try { OpCode(first & 0x0F) }
      catch {
        case _: java.util.NoSuchElementException => throw new WSParsingException(CloseCode.PROTOCOL_ERROR)
      }

    if ((first & 0x70) != 0) {
      throw new WSParsingException(CloseCode.PROTOCOL_ERROR)
    }
    if (OpCode.isControlCode(op) && !fin) {
      throw new WSParsingException(CloseCode.PROTOCOL_ERROR)
    }

    val second = nextByte()
    val masked = (second & 0x80) != 0

    var payload_length: Int = (0x7F & second).toByte

    if (payload_length == 126) {
      payload_length = (nextByteAsInt() << 8 | nextByteAsInt()) & 0xFFFF
      if (payload_length < 126) {
        throw new WSParsingException(CloseCode.PROTOCOL_ERROR)
      }
    } else if (payload_length == 127) {
      val payload_length_1: Long =
        (nextByte().toLong) << 56 |
          (nextByte().toLong) << 48 |
          (nextByte().toLong) << 40 |
          (nextByte().toLong) << 32 |
          nextByteAsInt() << 24 | nextByteAsInt() << 16 | nextByteAsInt() << 8 | nextByteAsInt()
      if (payload_length_1 < 65536) {
        throw new WSParsingException(CloseCode.PROTOCOL_ERROR)
      }
      if (payload_length_1 < 0 || payload_length_1 > Integer.MAX_VALUE) {
        throw new WSParsingException(CloseCode.MESSAGE_TOO_BIG)
      }
      payload_length = payload_length_1.toInt
    }

    if (OpCode.isControlCode(op)) {
      if (op == OpCode.CLOSE && payload_length == 1) {
        throw new WSParsingException(CloseCode.PROTOCOL_ERROR)
      }
      if (payload_length > 125) {
        throw new WSParsingException(CloseCode.PROTOCOL_ERROR)
      }
    }

    val mask_key = if (masked) {
      Array(nextByte(), nextByte(), nextByte(), nextByte())
    } else {
      Array[Byte]()
    }

    val payload = nextBytes(payload_length)

    if (4 == mask_key.length) {
      @inline @scala.annotation.tailrec def unmask(index: Int): Unit = {
        if (index < payload.length) {
          val original = payload(index)
          val mask = mask_key(index % 4)
          payload(index) = (original ^ mask).toByte
          unmask(index + 1)
        }
      }
      unmask(0)
    }
    if (op == OpCode.TEXT) {
      val s = new String(payload, "utf-8")
      WSResult.Emit(WSText(s, fin, payload, masked, mask_key), () => parseSafe(input, cursor, max_payload_length))
    } else if (op == OpCode.BINARY) {
      WSResult.Emit(WSBytes(payload, fin, masked, mask_key), () => parseSafe(input, cursor, max_payload_length))
    } else if (op == OpCode.CLOSE) {
      val (closeCode, reason) = if (2 <= payload.length) {
        val code = try {
          CloseCode((payload(0) & 0xff) << 8 | (payload(1) & 0xff))
        } catch {
          case _: java.util.NoSuchElementException => throw new WSParsingException(CloseCode.PROTOCOL_ERROR)
        }
        val reason = if (2 < payload.length) {
          new String(payload, 2, payload.length, "utf-8")
        } else {
          why(null)
        }
        (code, reason)
      } else {
        (CloseCode.NO_STATUS_CODE_IS_PRESENT, why(null))
      }
      WSResult.Emit(WSClose(closeCode, reason, payload, fin, masked, mask_key), () => WSResult.End)
    } else if (op == OpCode.PING) {
      WSResult.Emit(WSPing(payload, fin, masked, mask_key), () => parseSafe(input, cursor, max_payload_length))
    } else if (op == OpCode.PONG) {
      WSResult.Emit(WSPong(payload, fin, masked, mask_key), () => parseSafe(input, cursor, max_payload_length))
    } else if (op == OpCode.CONTINUATION) {
      WSResult.Emit(WSContinuation(payload, fin, masked, mask_key), () => parseSafe(input, cursor, max_payload_length))
    } else {
      WSResult.Error(CloseCode.RESERVED, "a ghost???")
    }
  }

  def render(bytes: Array[Byte], op: OpCode.Value, fin: Boolean = true, masked: Boolean = false, mask_key: Array[Byte] = EMPTY_BYTE_ARRAY): ByteString = {

    val x = ByteString.newBuilder
    @inline def putByte(b: Byte) = x.putByte(b)
    @inline def putIntAsByte(i: Int) = x.putByte(i.toByte)
    @inline def putBytes(src: Array[Byte]) = x.putBytes(src)

    val header = if (fin) {
      0x80 | (op.id & 0x0f)
    } else {
      op.id & 0x0f
    }
    putIntAsByte(header)

    val payload_length = bytes.length
    if (payload_length <= 125) {
      putIntAsByte(if (masked) 0x80 | payload_length else payload_length)
    } else if (payload_length <= 0xffff) {
      putIntAsByte(if (masked) 0xfe else 126)
      putIntAsByte(payload_length >>> 8)
      putIntAsByte(payload_length)
    } else {
      putIntAsByte(if (masked) 0xff else 127)
      putIntAsByte(payload_length >>> 56 & 0)
      putIntAsByte(payload_length >>> 48 & 0)
      putIntAsByte(payload_length >>> 40 & 0)
      putIntAsByte(payload_length >>> 32 & 0)
      putIntAsByte(payload_length >>> 24)
      putIntAsByte(payload_length >>> 16)
      putIntAsByte(payload_length >>> 8)
      putIntAsByte(payload_length)
    }

    if (masked) {
      putBytes(mask_key)
      @inline @scala.annotation.tailrec def write(offset: Int): Unit = {
        if (offset < bytes.length) {
          val original = bytes(offset)
          val mask = mask_key(offset % 4)
          putIntAsByte(original ^ mask)
          write(offset + 1)
        }
      }
      write(0)
    } else {
      putBytes(bytes)
    }
    x.result()

  }
  def render(frame: WSFrame): ByteString = {
    import frame._
    render(bytes, op, fin, masked, mask_key)
  }

  def render(s: String): ByteString = {
    render(WSText(s, true, s.getBytes("utf-8"), false, EMPTY_BYTE_ARRAY))
  }
}