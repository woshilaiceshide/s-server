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

import woshilaiceshide.sserver.nio._
import woshilaiceshide.sserver.httpd.WebSocket13
import woshilaiceshide.sserver.httpd.WebSocket13.WebSocketAcceptance

object HttpTransformer {

  private def safeOp[T](x: => T) = try { x } catch { case _: Throwable => {} }

  private[HttpTransformer] final case class Node(value: HttpRequestPart, closeAfterResponseCompletion: Boolean, channelWrapper: ChannelWrapper, var next: Node)
  private[HttpTransformer] final case class DataToSwitch(byteString: ByteString, offset: Int, channelWrapper: ChannelWrapper)

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

//please refer to spray.can.server.RequestParsing
//transform bytes to http
class HttpTransformer(private[this] var handler: PlainHttpChannelHandler,
  val original_parser: HttpTransformer.RevisedHttpRequestPartParser = HttpTransformer.default_parser,
  max_request_in_pipeline: Int = 1)
    extends ChannelHandler {

  import HttpTransformer._

  private val max_request_in_pipeline_1 = Math.max(1, max_request_in_pipeline)

  private[can] def check(channelWrapper: ChannelWrapper, closeAfterEnd: Boolean): Unit = {
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

  def channelOpened(channelWrapper: ChannelWrapper): Unit = {}

  private var _inputEnded = false
  def inputEnded(channelWrapper: ChannelWrapper) = {
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

  def bytesReceived(byteBuffer: java.nio.ByteBuffer, channelWrapper: ChannelWrapper): ChannelHandler = {
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

  def check(channelWrapper: ChannelWrapper): ChannelHandler = {
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
            val btw = new WebsocketTransformer(h.handler, new WebSocketChannelWrapper(data_to_switching.channelWrapper), WebSocket13.default_parser(h.max_payload_length))
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
          if (!p.channelWritable()) {
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
  def bytesReceived1(byteBuffer: java.nio.ByteBuffer, channelWrapper: ChannelWrapper): ChannelHandler = {

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
                        val tmp = new WebsocketTransformer(h.handler, new WebSocketChannelWrapper(channelWrapper), WebSocket13.default_parser(h.max_payload_length))
                        if (null != byteString && byteString.length > lastOffset)
                          tmp.bytesReceived(byteString, lastOffset, channelWrapper)
                        tmp
                      }
                      case _ => { channelWrapper.closeChannel(true); this }
                    }

                  }
                  case p: HttpRequestProcessor => {
                    if (!p.channelWritable()) {
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
  def channelIdled(channelWrapper: ChannelWrapper): Unit = {}
  def channelWritable(channelWrapper: ChannelWrapper): Unit = {
    if (null != processor) { processor.channelWritable() }
  }
  def channelClosed(channelWrapper: ChannelWrapper, cause: ChannelClosedCause.Value, attachment: Option[_]): Unit = {
    data_to_switching = null
    if (null != processor) { safeOp { processor.close() }; processor = null; }
    head = null
    tail = null
    pipeline_size = 0
    handler = null
    parser = null
  }
}