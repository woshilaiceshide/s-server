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

  //TODO print exception's all fields
  private def safeOp[T](x: => T) =
    try {
      x
    } catch {
      case ex: Throwable => {

        Console.err.print(ex.getMessage)
        Console.err.print(" ")
        ex.printStackTrace(Console.err)

      }
    }

  private[HttpTransformer] final case class Node(value: HttpRequestPart, closeAfterResponseCompletion: Boolean, channelWrapper: ChannelWrapper, var next: Node)

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

  private[can] object FakeException extends Exception with scala.util.control.NoStackTrace
  private[can] class RevisedHttpRequestPartParser(parser_setting: ParserSettings, rawRequestUriHeader: Boolean)
      extends HttpRequestPartParser(parser_setting, rawRequestUriHeader)() {

    //this instance will be used from the very beginning to the end fo this channel
    var lastOffset = -1
    var lastInput: ByteString = _
    var just_check_positions = false

    //1. method invocation is lay binding in java/scala
    //2. super.parseMessageSafe will invoke parseMessageSafe, which is RevisedHttpRequestPartParser's parseMessageSafe.
    override def parseMessageSafe(input: ByteString, offset: Int = 0): Result = {
      if (just_check_positions) {
        lastOffset = offset
        lastInput = input
        throw FakeException
      } else {
        super.parseMessageSafe(input, offset)
      }
    }

    /**
     * I've seen that 'copyWith' will be invoked in spray-scan's pipeline only, and not in this project.
     */
    override def copyWith(warnOnIllegalHeader: ErrorInfo ⇒ Unit): HttpRequestPartParser = throw new UnsupportedOperationException()
  }

  def default_parser = new RevisedHttpRequestPartParser(default_parser_settings, false)

  final class MyChannelInformation(channel: HttpChannel) extends ChannelInformation {
    def remoteAddress = channel.remoteAddress
    def localAddress = channel.localAddress
  }
}

/**
 * transform bytes to http.
 * please refer to spray.can.server.RequestParsing.
 * when counting messages in the pipeline(see "http pipelining"), i take every chunked message into account as intended.
 *
 * TODO need to limit the un-processed bytes(related to the messages in pipeline).
 */
class HttpTransformer(handler: HttpChannelHandler,
  val original_parser: HttpTransformer.RevisedHttpRequestPartParser = HttpTransformer.default_parser,
  max_request_in_pipeline: Int = 1)
    extends ChannelHandler {

  import HttpTransformer._

  private val max_request_in_pipeline_1 = Math.max(1, max_request_in_pipeline)

  private[this] var current_sink: ResponseSink = null

  private[this] var current_http_channel: HttpChannel = _

  @inline private def has_next(): Boolean = null != head

  private var parser: _root_.spray.can.parsing.Parser = original_parser

  def lastOffset = original_parser.lastOffset
  def lastInput = original_parser.lastInput

  def channelOpened(channelWrapper: ChannelWrapper): Unit = {}

  private var head: Node = null
  private var tail: Node = null
  private var pipeline_size = 0

  @inline private def en_queue(request: HttpRequestPart, closeAfterResponseCompletion: Boolean, channelWrapper: ChannelWrapper) = {
    if (null == head) {
      head = Node(request, closeAfterResponseCompletion, channelWrapper, null)
      tail = head
      pipeline_size = 1
    } else {
      if (pipeline_size >= max_request_in_pipeline_1) {
        throw new RuntimeException("too many requests in pipeline!")
      } else {
        tail.next = Node(request, closeAfterResponseCompletion, channelWrapper, null)
        tail = tail.next
        pipeline_size = pipeline_size + 1
      }
    }
  }
  @inline private def de_queue() = {
    if (head == null) {
      null
    } else {
      val tmp = head
      head = head.next
      pipeline_size = pipeline_size - 1
      tmp
    }
  }
  @inline private def clear_queue() = {
    head = null
    tail = null
    pipeline_size = 0
  }
  @inline private def is_queue_empty() = pipeline_size == 0

  def bytesReceived(byteBuffer: java.nio.ByteBuffer, channelWrapper: ChannelWrapper): ChannelHandler = {

    //a reasonable request flow is produced
    val result = parser.apply(ByteString(byteBuffer))

    @scala.annotation.tailrec def process(result: Result): ChannelHandler = {
      result match {

        //closeAfterResponseCompletion will be fine even if it's a 'MessageChunk'
        case Result.Emit(request: HttpRequestPart, closeAfterResponseCompletion, continue) => {

          request match {
            case x: ChunkedRequestStart => {
              if (current_http_channel != null) {
                en_queue(x, closeAfterResponseCompletion, channelWrapper)
                process(continue())
              } else {
                if (current_sink != null) {
                  current_sink = null
                }

                current_http_channel = new HttpChannel(channelWrapper, closeAfterResponseCompletion, x.request.method, x.request.protocol)
                val action = handler.requestReceived(x.request, current_http_channel, AChunkedRequestStart)
                action match {
                  case ResponseAction.AcceptChunking(h) => {
                    current_sink = h
                    process(continue())
                  }
                  case ResponseAction.ResponseNormally => {
                    process(continue())
                  }
                  case _ => {
                    channelWrapper.closeChannel(false)
                    null
                  }
                }

              }
            }
            case x: MessageChunk => {

              if (head == null) {
                if (current_sink != null) {
                  current_sink match {
                    case c: ChunkedRequestHandler => c.chunkReceived(x)
                    case _ => channelWrapper.closeChannel(true)
                  }
                }
                process(continue())
              } else {
                en_queue(x, closeAfterResponseCompletion, channelWrapper)
                process(continue())
              }
            }
            case x: ChunkedMessageEnd => {

              if (head == null) {
                if (current_sink != null) {
                  current_sink match {
                    case c: ChunkedRequestHandler => c.chunkEnded(x)
                    case _ => channelWrapper.closeChannel(true)
                  }
                  //do not make it null now!!!
                  //current_sink = null
                }
                process(continue())
              } else {
                en_queue(x, closeAfterResponseCompletion, channelWrapper)
                process(continue())
              }
            }
            case x: HttpRequest => {

              if (current_http_channel != null) {
                en_queue(x, closeAfterResponseCompletion, channelWrapper)
                process(continue())
              } else {
                if (current_sink != null) {
                  current_sink = null
                }

                current_http_channel = new HttpChannel(channelWrapper, closeAfterResponseCompletion, x.method, x.protocol)
                val classifier = DynamicRequestClassifier(x)
                val action = handler.requestReceived(x, current_http_channel, classifier)
                action match {
                  case ResponseAction.ResponseNormally => {
                    process(continue())
                  }
                  case ResponseAction.AcceptWebsocket(factory) => {

                    original_parser.just_check_positions = true
                    try { continue() } catch { case FakeException => {} }
                    //!!!
                    original_parser.just_check_positions = false

                    val websocket_channel = new WebSocketChannel(channelWrapper, closeAfterResponseCompletion, x.method, x.protocol)
                    val (websocket_channel_handler, wsframe_parser) = factory(websocket_channel)
                    val websocket = new WebsocketTransformer(websocket_channel_handler, websocket_channel, wsframe_parser)

                    if (null != lastInput && lastInput.length > lastOffset) {
                      //DO NOT invoke websocket's bytesReceived here, or dead locks / too deep recursion will be found.
                      //websocket.bytesReceived(lastInput.drop(lastOffset).asByteBuffer, channelWrapper)
                      throw new RuntimeException("no data should be here because handshake does not complete.")
                    }
                    websocket
                  }
                  case ResponseAction.ResponseWithASink(sink) => {
                    current_sink = sink
                    process(continue())
                  }
                  case _ => {
                    channelWrapper.closeChannel(false)
                    null
                  }
                }

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

  def writtenHappened(channelWrapper: ChannelWrapper): ChannelHandler = {

    if (null == current_http_channel || (current_http_channel != null && current_http_channel.isCompleted)) {

      current_http_channel = null
      current_sink = null

      val next = de_queue()
      if (null != next) {
        val request = next.value
        val closeAfterResponseCompletion = next.closeAfterResponseCompletion
        next.value match {

          case x: ChunkedRequestStart => {

            current_http_channel = new HttpChannel(channelWrapper, closeAfterResponseCompletion, x.request.method, x.request.protocol)
            val action = handler.requestReceived(x.request, current_http_channel, AChunkedRequestStart)
            action match {
              case ResponseAction.AcceptChunking(h) => {
                current_sink = h
                this
              }
              case ResponseAction.ResponseNormally => {
                this
              }
              case _ => {
                channelWrapper.closeChannel(false)
                null
              }
            }

          }

          case x: MessageChunk => this
          case x: ChunkedMessageEnd => this

          case x: HttpRequest => {

            current_http_channel = new HttpChannel(channelWrapper, closeAfterResponseCompletion, x.method, x.protocol)
            val classifier = DynamicRequestClassifier(x)
            val action = handler.requestReceived(x, current_http_channel, classifier)
            action match {
              case ResponseAction.ResponseNormally => {
                this
              }
              case ResponseAction.AcceptWebsocket(factory) => {

                val websocket_channel = new WebSocketChannel(channelWrapper, closeAfterResponseCompletion, x.method, x.protocol)
                val (websocket_channel_handler, wsframe_parser) = factory(websocket_channel)
                val websocket = new WebsocketTransformer(websocket_channel_handler, websocket_channel, wsframe_parser)

                if (!is_queue_empty()) {
                  //DO NOT invoke websocket's bytesReceived here, or dead locks / too deep recursion will be found.
                  //websocket.bytesReceived(lastInput.drop(lastOffset).asByteBuffer, channelWrapper)
                  throw new RuntimeException("no data should be here because handshake does not complete.")
                }
                websocket
              }
              case ResponseAction.ResponseWithASink(sink) => {
                current_sink = sink
                this
              }
              case _ => {
                channelWrapper.closeChannel(false)
                null
              }
            }

          }

        }
      } else {
        this
      }

    } else {
      this
    }

  }

  def channelIdled(channelWrapper: ChannelWrapper): Unit = {
    if (null != current_sink) current_sink.channelIdled()
  }

  def channelWritable(channelWrapper: ChannelWrapper): Unit = {
    if (null != current_sink) current_sink.channelIdled()
  }

  def inputEnded(channelWrapper: ChannelWrapper): Unit = {
    //nothing else
  }

  def channelClosed(channelWrapper: ChannelWrapper, cause: ChannelClosedCause.Value, attachment: Option[_]): Unit = {

    clear_queue()

    current_http_channel = null

    if (null != current_sink) {
      current_sink.channelClosed()
      current_sink = null
    }

    parser = null
  }
}