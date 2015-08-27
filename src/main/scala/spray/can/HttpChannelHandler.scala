package spray.can

import akka.util._
import _root_.spray.can.parsing.ParserSettings
import _root_.spray.can.parsing.HttpRequestPartParser
import _root_.spray.can.parsing.Result
import _root_.spray.http._
import _root_.spray.http.HttpRequest
import _root_.spray.http.HttpResponse
import _root_.spray.http.StatusCodes
import _root_.spray.http.HttpHeader
import _root_.spray.http.ByteArrayRendering
import _root_.spray.http.HttpResponsePart
import _root_.spray.http.HttpRequestPart
import _root_.spray.can.rendering.ResponsePartRenderingContext
import _root_.spray.can.rendering.ResponseRenderingComponent
import _root_.spray.can.rendering.ResponseRenderingComponent
import woshilaiceshide.sserver.nio.AChannel
import woshilaiceshide.sserver.nio.NioSocketServer
import woshilaiceshide.sserver.nio.ChannelHandler
import woshilaiceshide.sserver.nio.ChannelHandlerFactory
import woshilaiceshide.sserver.httpd.WebSocket13
import woshilaiceshide.sserver.httpd.WebSocket13.WebSocketShow

import woshilaiceshide.sserver.nio.NioSocketServer._

class HttpChannelHandlerFactory(plain_http_channel_handler: PlainHttpChannelHandler, max_request_in_pipeline: Int = 1) extends ChannelHandlerFactory {

  def handler = Some(new ByteChannelToHttpChannel(plain_http_channel_handler, max_request_in_pipeline = max_request_in_pipeline))
  def getChannelHandler(aChannel: AChannel): Option[ChannelHandler] = handler
}

sealed abstract class HttpChannelWrapper(
    channelWrapper: NioSocketServer#ChannelWrapper,
    private[this] var closeAfterEnd: Boolean,
    private[this] val httpChannelHandler: ByteChannelToHttpChannel) extends ResponseRenderingComponent {

  def serverHeaderValue: String = woshilaiceshide.sserver.httpd.HttpdInforamtion.VERSION
  def chunklessStreaming: Boolean = false
  def transparentHeadRequests: Boolean = false

  def chunked: Boolean = false

  private var finished = false

  def writeResponse(response: HttpResponsePart) = {
    val (_finished, wr) = synchronized {

      if (finished) {
        throw new RuntimeException("request is already served. DO NOT DO IT AGAIN!")
      }
      response match {
        case _: HttpResponse      => finished = true
        case _: ChunkedMessageEnd => finished = true
        case _                    => {}
      }

      val r = new ByteArrayRendering(1024)
      val ctx = new ResponsePartRenderingContext(responsePart = response)
      val closeMode = renderResponsePartRenderingContext(r, ctx, akka.event.NoLogging)

      val write_result = channelWrapper.write(r.get)

      val closeNow = closeMode.shouldCloseNow(ctx.responsePart, closeAfterEnd)
      if (closeMode == CloseMode.CloseAfterEnd) closeAfterEnd = true
      if (closeNow) {
        //closeAfterEnd = false
        channelWrapper.closeChannel(false)
      }
      (finished, write_result)
    }

    if (finished) {
      httpChannelHandler.check(channelWrapper, closeAfterEnd)
    }
    wr
  }

  def toWebSocketChannelHandler(request: HttpRequest, extraHeaders: List[HttpHeader], max_payload_length: Int, born: WebSocketChannelWrapper => WebSocketChannelHandler) = {
    WebSocket13.showResponse(request) match {
      case WebSocketShow.Ok(response) => {
        writeResponse(response)
        val channel = new WebSocketChannelWrapper(channelWrapper)
        LengthedWebSocketChannelHandler(born(channel), max_payload_length)
      }
      case WebSocketShow.Failed(response) => {
        writeResponse(response)
        channelWrapper.closeChannel(false)
        null
      }
      case WebSocketShow.ERROR => {
        channelWrapper.closeChannel(true)
        null
      }
    }

  }

  def respond(r: => HttpResponse) = new HttpRequestProcessor() {
    def channelWrapper = HttpChannelWrapper.this
    private lazy val r1 = r
    def close() {}
    private var _finished = false
    def finished = _finished
    def becomeWritable() = {
      if (!_finished) {
        _finished = HttpChannelWrapper.this.writeResponse(r1) == WriteResult.WR_OK
      }
      _finished
    }
  }

  def respondAsynchronously(r: => scala.concurrent.Future[HttpResponse])(implicit ec: scala.concurrent.ExecutionContext) = new HttpRequestProcessor() {
    def channelWrapper = HttpChannelWrapper.this
    private val r1 = r.recover {
      case _ => HttpResponse(500)
    }
    r1.onSuccess {
      case response => synchronized {
        resp = response
        becomeWritable()
      }
    }
    private var resp: HttpResponse = null
    def close() {}
    private var _finished = false
    def finished = synchronized { _finished }
    def becomeWritable() = synchronized {
      if (!_finished && resp != null) {
        _finished = HttpChannelWrapper.this.writeResponse(resp) == WriteResult.WR_OK
        _finished
      } else {
        false
      }
    }
  }

}

final class ChunkedHttpChannelWrapper(
  channelWrapper: NioSocketServer#ChannelWrapper,
  closeAfterEnd: Boolean,
  httpChannelHandler: ByteChannelToHttpChannel)
    extends HttpChannelWrapper(channelWrapper, closeAfterEnd, httpChannelHandler) {
  final override def chunked = true
}
final class PlainHttpChannelWrapper(
  channelWrapper: NioSocketServer#ChannelWrapper,
  closeAfterEnd: Boolean,
  httpChannelHandler: ByteChannelToHttpChannel)
    extends HttpChannelWrapper(channelWrapper, closeAfterEnd, httpChannelHandler) {
  final override def chunked = true
}

sealed trait HttpChannelHandler
trait PlainHttpChannelHandler extends HttpChannelHandler {
  def requestReceived(request: HttpRequestPart, channel: HttpChannelWrapper): HttpRequestProcessor
  def channelClosed(channel: HttpChannelWrapper): Unit
}

trait HttpRequestProcessor {

  def channelWrapper: HttpChannelWrapper

  def chunkReceived(x: MessageChunk): Unit = {}
  def chunkEnded(x: ChunkedMessageEnd): Unit = {}

  //I's completed just now if I return true!
  def becomeWritable(): Boolean
  def close(): Unit
  def finished: Boolean
}

class WebSocketChannelWrapper(channelWarpper: NioSocketServer#ChannelWrapper) {
  import WebSocket13._
  def writeString(s: String) = {
    val rendered = render(s)
    channelWarpper.write(rendered.toArray)
  }
  def writeBytes(bytes: Array[Byte]) = {
    val rendered = render(bytes, WebSocket13.OpCode.BINARY)
    channelWarpper.write(rendered.toArray)
  }
  def close(closeCode: Option[CloseCode.Value] = CloseCode.NORMAL_CLOSURE_OPTION) = {
    val frame = WSClose(closeCode.getOrElse(CloseCode.NORMAL_CLOSURE), why(null), EMPTY_BYTE_ARRAY, true, false, EMPTY_BYTE_ARRAY)
    val rendered = render(frame)
    channelWarpper.write(rendered.toArray)
    channelWarpper.closeChannel(false, closeCode)
  }
  def ping() = {
    val rendered = render(WebSocket13.EMPTY_BYTE_ARRAY, WebSocket13.OpCode.PING)
    channelWarpper.write(rendered.toArray)
  }
}
final case class LengthedWebSocketChannelHandler(val handler: WebSocketChannelHandler,
                                                 val max_payload_length: Int) extends HttpRequestProcessor {

  def no = throw new RuntimeException("this method should not be invoked anywhere.")
  def channelWrapper: HttpChannelWrapper = no

  override def chunkReceived(x: MessageChunk): Unit = no
  override def chunkEnded(x: ChunkedMessageEnd): Unit = no

  def becomeWritable(): Boolean = no
  def close(): Unit = no
  def finished: Boolean = no

}
trait WebSocketChannelHandler extends HttpChannelHandler {
  def pongReceived(frame: WebSocket13.WSFrame): Unit
  def frameReceived(frame: WebSocket13.WSFrame): Unit
  def fireClosed(code: WebSocket13.CloseCode.Value, reason: String): Unit
  def inputEnded(): Unit

  def channelWrapper: HttpChannelWrapper =
    throw new RuntimeException("this method should not be invoked anywhere.")

  def becomeWritable(): Unit
  def close(): Unit =
    throw new RuntimeException("this method should not be invoked anywhere.")
  def finished: Boolean =
    throw new RuntimeException("this method should not be invoked anywhere.")
}

object ByteChannelToHttpChannel {

  private def safeOp[T](x: => T) = try { x } catch { case _: Throwable => {} }

  val headerValueCacheLimits = {
    import com.typesafe.config._
    val s = """
{
      default = 12
      Content-MD5 = 0
      Date = 0
      If-Match = 0
      If-Modified-Since = 0
      If-None-Match = 0
      If-Range = 0
      If-Unmodified-Since = 0
      User-Agent = 32
}
    """
    val options = ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF)
    val config = ConfigFactory.parseString(s, options)
    import scala.collection.JavaConverters._
    config.entrySet.asScala.map(kvp => kvp.getKey -> config.getInt(kvp.getKey))(collection.breakOut).toMap
  }

  val default_parser_settings = ParserSettings(
    maxUriLength = 256,
    maxResponseReasonLength = 128,
    maxHeaderNameLength = 128,
    maxHeaderValueLength = 128,
    maxHeaderCount = 128,
    maxContentLength = 4 * 1024,
    maxChunkExtLength = 4 * 1024,
    maxChunkSize = 4 * 1024,
    autoChunkingThreshold = 1024 * 1024 * 8,
    uriParsingMode = _root_.spray.http.Uri.ParsingMode.Relaxed,
    illegalHeaderWarnings = false,
    sslSessionInfoHeader = false,
    headerValueCacheLimits = headerValueCacheLimits)

  class RevisedHttpRequestPartParser(parser_setting: ParserSettings, rawRequestUriHeader: Boolean)
      extends HttpRequestPartParser(parser_setting, rawRequestUriHeader)() {
    var lastOffset = -1
    override def parseMessageSafe(input: ByteString, offset: Int = 0): Result = {
      lastOffset = offset
      super.parseMessageSafe(input, offset)
    }
  }

  def default_parser = new RevisedHttpRequestPartParser(default_parser_settings, false)
}

private[can] case class Node(value: HttpRequestPart, closeAfterResponseCompletion: Boolean, channelWrapper: NioSocketServer#ChannelWrapper, var next: Node)
private[can] case class DataToSwitch(byteString: ByteString, offset: Int, channelWrapper: NioSocketServer#ChannelWrapper)
//please refer to spray.can.server.RequestParsing
class ByteChannelToHttpChannel(private[this] var handler: PlainHttpChannelHandler,
                               val original_parser: ByteChannelToHttpChannel.RevisedHttpRequestPartParser = ByteChannelToHttpChannel.default_parser,
                               max_request_in_pipeline: Int = 1)
    extends ChannelHandler {

  import ByteChannelToHttpChannel._

  private val max_request_in_pipeline_1 = Math.max(1, max_request_in_pipeline)

  private[can] def check(channelWrapper: NioSocketServer#ChannelWrapper, closeAfterEnd: Boolean): Unit = {
    //use "post(...)" to keep things run on its way.
    if (channelWrapper.is_in_io_worker_thread()) {
      if (closeAfterEnd || (!has_next() && _inputEnded)) {
        channelWrapper.closeChannel(false)
      } else if (has_next()) {
        channelWrapper.post(new Runnable() {
          def run() {
            bytesReceived(null, channelWrapper)
          }
        })
      }
    } else {
      channelWrapper.post(new Runnable() {
        def run() { check(channelWrapper, closeAfterEnd) }
      })
    }
  }

  @inline private def has_next(): Boolean = null != head

  //am i switching to websocket?
  private def switching = data_to_switching != null
  //if too many bytes here??? close it! the max is 1024 bytes.
  private var bytes_to_switching: ByteString = null
  private var data_to_switching: DataToSwitch = null
  private var parser: _root_.spray.can.parsing.Parser = original_parser
  def lastOffset = original_parser.lastOffset

  def channelOpened(channelWrapper: NioSocketServer#ChannelWrapper): Unit = {}

  private var _inputEnded = false
  def inputEnded(channelWrapper: NioSocketServer#ChannelWrapper) = {
    _inputEnded = true
    if (!has_next) {
      channelWrapper.closeChannel(false)
    }
  }

  private var head: Node = null
  private var tail: Node = null
  private var pipeline_size = 0

  @inline private def chunking = if (null == processor) { false } else { processor.channelWrapper.chunked }

  private var processor: HttpRequestProcessor = null

  def bytesReceived(byteBuffer: java.nio.ByteBuffer, channelWrapper: NioSocketServer#ChannelWrapper): ChannelHandler = {
    if (null == byteBuffer) {
      check(channelWrapper)
    } else if (switching) {
      if (128 < byteBuffer.remaining()) {
        throw new RuntimeException("too many bytes in the websocket channel")
      } else if (null == bytes_to_switching)
        bytes_to_switching = ByteString(byteBuffer)
      else if (bytes_to_switching.length + byteBuffer.remaining() > 1024) {
        //channelWrapper.closeChannel(true)
        throw new RuntimeException("too many bytes in the websocket channel")
      } else {
        bytes_to_switching = bytes_to_switching ++ ByteString(byteBuffer)
      }
      this
    } else {
      bytesReceived1(byteBuffer, channelWrapper)
    }
  }

  def check(channelWrapper: NioSocketServer#ChannelWrapper): ChannelHandler = {
    if (head != null) {
      val tmp = head
      head = head.next
      if (null == head) tail = head
      pipeline_size = pipeline_size - 1
      //it should be PlainHttpChannelWrapper
      val channel = new PlainHttpChannelWrapper(tmp.channelWrapper, tmp.closeAfterResponseCompletion, this)
      handler.requestReceived(tmp.value, channel) match {
        case null => {
          tmp.channelWrapper.closeChannel(true)
          this
        }
        case h: LengthedWebSocketChannelHandler => {
          if (switching) {
            //switching protocol
            val btw = new ByteChannelToWebsocketChannel(h.handler, new WebSocketChannelWrapper(data_to_switching.channelWrapper), WebSocket13.default_parser(h.max_payload_length))
            if (null != data_to_switching)
              btw.bytesReceived(data_to_switching.byteString, data_to_switching.offset, data_to_switching.channelWrapper)

            if (null != bytes_to_switching) {
              val bytes = bytes_to_switching
              bytes_to_switching = null
              data_to_switching = null
              btw.bytesReceived(bytes, 0, tmp.channelWrapper)
            }
            btw
          } else {
            tmp.channelWrapper.closeChannel(true)
            this
          }
        }
        case p: HttpRequestProcessor => {
          if (!p.becomeWritable()) {
            processor = p
          }
          this
        }
      }
    } else {
      if (_inputEnded) {
        channelWrapper.closeChannel(false)
      }
      this
    }
  }
  def bytesReceived1(byteBuffer: java.nio.ByteBuffer, channelWrapper: NioSocketServer#ChannelWrapper): ChannelHandler = {

    val byteString = ByteString(byteBuffer)

    val result = parser.apply(byteString)

    @scala.annotation.tailrec def process(result: Result): ChannelHandler = {
      result match {
        case Result.Emit(request: HttpRequestPart, closeAfterResponseCompletion, continue) => {
          request match {
            case x: ChunkedRequestStart => {
              if (processor == null) {
                val channel = new ChunkedHttpChannelWrapper(channelWrapper, closeAfterResponseCompletion, this)
                processor = handler.requestReceived(request, channel)
              } else {
                if (null == head) {
                  head = Node(x, closeAfterResponseCompletion, channelWrapper, null)
                  tail = head
                  pipeline_size = 1
                } else {
                  if (pipeline_size >= max_request_in_pipeline_1) {
                    throw new RuntimeException("too many requests in pipeline!")
                  } else {
                    tail.next = Node(x, closeAfterResponseCompletion, channelWrapper, null)
                    tail = tail.next
                    pipeline_size = pipeline_size + 1
                  }

                }
              }
              process(continue())
            }
            case x: ChunkedMessageEnd => {
              //response may be completed without every chunk received.
              if (null != processor) processor.chunkEnded(x)
              process(continue())
            }
            case x: MessageChunk => {
              //response may be completed without every chunk received.
              if (null != processor) processor.chunkReceived(x)
              process(continue())
            }
            case x: HttpRequest => {

              //it's not strict enough!
              if (processor == null) {
                val channel = new PlainHttpChannelWrapper(channelWrapper, closeAfterResponseCompletion, this)
                handler.requestReceived(x, channel) match {
                  case null => {
                    channelWrapper.closeChannel(true)
                    this
                  }
                  case h: LengthedWebSocketChannelHandler => {
                    //use a http parser for websocket data
                    continue() match {
                      case Result.ParsingError(_, _) | Result.NeedMoreData(_) => {
                        //switching protocol
                        val tmp = new ByteChannelToWebsocketChannel(h.handler, new WebSocketChannelWrapper(channelWrapper), WebSocket13.default_parser(h.max_payload_length))
                        if (null != byteString && byteString.length > lastOffset)
                          tmp.bytesReceived(byteString, lastOffset, channelWrapper)
                        tmp
                      }
                      case _ => { channelWrapper.closeChannel(true); this }
                    }

                  }
                  case p: HttpRequestProcessor => {
                    if (!p.becomeWritable()) {
                      processor = p
                    }
                    process(continue())
                  }
                }

              } else {
                if (null == head) {
                  head = Node(x, closeAfterResponseCompletion, channelWrapper, null)
                  tail = head
                  pipeline_size = 1
                } else {
                  if (pipeline_size >= max_request_in_pipeline_1) {
                    throw new RuntimeException("too many requests in pipeline!")
                  } else {
                    tail.next = Node(x, closeAfterResponseCompletion, channelWrapper, null)
                    tail = tail.next
                    pipeline_size = pipeline_size + 1
                  }
                }
                if (WebSocket13.isAWebSocketRequest(x)) {
                  continue() match {
                    //switching protocol
                    case Result.ParsingError(_, _) | Result.NeedMoreData(_) => {
                      if (lastOffset < byteString.length) {
                        data_to_switching = DataToSwitch(byteString, lastOffset, channelWrapper)
                      }
                    }
                    case _ => { channelWrapper.closeChannel(true); this }
                  }
                }

                process(continue())
              }
            }
          }
        }
        case Result.NeedMoreData(parser1) => {
          parser = parser1
          this
        }
        case x => {
          channelWrapper.closeChannel(true)
          this
        }
      }
    }
    process(result)
  }
  def channelIdeled(channelWrapper: NioSocketServer#ChannelWrapper): Unit = {}
  def becomeWritable(channelWrapper: NioSocketServer#ChannelWrapper): Unit = {
    if (null != processor) { processor.becomeWritable() }
  }
  def channelClosed(channelWrapper: NioSocketServer#ChannelWrapper, cause: NioSocketServer.ChannelClosedCause.Value, attachment: Option[_]): Unit = {
    data_to_switching = null
    if (null != processor) { safeOp { processor.close() }; processor = null; }
    head = null
    tail = null
    pipeline_size = 0
    handler = null
    parser = null
  }
}

class ByteChannelToWebsocketChannel(
  handler: WebSocketChannelHandler, channel: WebSocketChannelWrapper,
  private[this] var parser: WebSocket13.WSFrameParser)
    extends ChannelHandler {

  def channelOpened(channelWrapper: NioSocketServer#ChannelWrapper): Unit = {}

  def inputEnded(channelWrapper: NioSocketServer#ChannelWrapper) = handler.inputEnded()

  def bytesReceived(byteString: ByteString, offset: Int, channelWrapper: NioSocketServer#ChannelWrapper): ChannelHandler = {
    import WebSocket13._
    val result = parser(byteString)
    @scala.annotation.tailrec def process(result: WSResult): ChannelHandler = {
      result match {
        case WSResult.Emit(frame, continue) => {
          frame match {
            case x: WSPong => {
              handler.pongReceived(x)
              process(continue())
            }
            case x: WSClose => {
              handler.frameReceived(x)
              channelWrapper.closeChannel(false, CloseCode.NORMAL_CLOSURE_OPTION)
              this
            }
            case x => { handler.frameReceived(x); process(continue()) }
          }

        }
        case WSResult.NeedMoreData(parser1) => {
          parser = parser1
          this
        }
        case WSResult.End                      => { /* nothing to do */ null /*this*/ }
        case WSResult.Error(closeCode, reason) => { channelWrapper.closeChannel(false); null /*this*/ }
      }
    }
    process(result)
  }
  def bytesReceived(byteBuffer: java.nio.ByteBuffer, channelWrapper: NioSocketServer#ChannelWrapper): ChannelHandler = {

    val byteString = ByteString(byteBuffer)
    bytesReceived(byteString, 0, channelWrapper)

  }
  def channelIdeled(channelWrapper: NioSocketServer#ChannelWrapper): Unit = {}
  def becomeWritable(channelWrapper: NioSocketServer#ChannelWrapper): Unit = {
    if (null != handler) handler.becomeWritable()
  }
  def channelClosed(channelWrapper: NioSocketServer#ChannelWrapper, cause: NioSocketServer.ChannelClosedCause.Value, attachment: Option[_]): Unit = {
    cause match {
      case NioSocketServer.ChannelClosedCause.BY_BIZ => {
        val closeCode = attachment match {
          case Some(code: WebSocket13.CloseCode.Value) => code
          case _                                       => WebSocket13.CloseCode.CLOSED_ABNORMALLY
        }
        handler.fireClosed(closeCode, cause.toString())
      }

      case NioSocketServer.ChannelClosedCause.SERVER_STOPPING =>
        handler.fireClosed(WebSocket13.CloseCode.GOING_AWAY, cause.toString())
      case _ =>
        handler.fireClosed(WebSocket13.CloseCode.CLOSED_ABNORMALLY, cause.toString())
    }

  }
}