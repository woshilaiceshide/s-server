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
        ex.printStackTrace()
      }
    }

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

    //I've seen that 'copyWith' will be invoked in spray-scan's pipeline only, and not in this project.
    override def copyWith(warnOnIllegalHeader: ErrorInfo ⇒ Unit): HttpRequestPartParser = throw new UnsupportedOperationException()
  }

  def default_parser = new RevisedHttpRequestPartParser(default_parser_settings, false)

  final class MyChannelInformation(channel: HttpChannelWrapper) extends ChannelInformation {
    def remoteAddress = channel.remoteAddress
    def localAddress = channel.localAddress
  }
}

//please refer to spray.can.server.RequestParsing
//transform bytes to http
//TODO counting requests is wrong in pipelining
class HttpTransformer(plain_handler: PlainHttpChannelHandler,
  val original_parser: HttpTransformer.RevisedHttpRequestPartParser = HttpTransformer.default_parser,
  max_request_in_pipeline: Int = 1)
    extends TrampledChannelHandler {

  import HttpTransformer._

  private[this] var chunked_handler: ChunkedHttpChannelHandler = null

  private val max_request_in_pipeline_1 = Math.max(1, max_request_in_pipeline)

  private[this] var current_http_channel: HttpChannelWrapper = _

  @inline private def has_next(): Boolean = null != head

  private var parser: _root_.spray.can.parsing.Parser = original_parser

  def lastOffset = original_parser.lastOffset
  def lastInput = original_parser.lastInput

  def channelOpened(channelWrapper: ChannelWrapper): Unit = {}

  private var head: Node = null
  private var tail: Node = null
  private var pipeline_size = 0

  def bytesReceived(byteBuffer: java.nio.ByteBuffer, channelWrapper: ChannelWrapper): HandledResult = {

    val result = parser.apply(ByteString(byteBuffer))

    @scala.annotation.tailrec def process(result: Result): HandledResult = {
      result match {

        //closeAfterResponseCompletion will be fine even if it's a 'MessageChunk'
        case Result.Emit(request: HttpRequestPart, closeAfterResponseCompletion, continue) => {

          request match {
            case x: ChunkedRequestStart => {

              if (chunked_handler != null) {
                //i'm already in chunked mode.
                channelWrapper.closeChannel(true)
                null

              } else if (current_http_channel != null) {
                //the previous request's handlement is not finished.
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
                process(continue())
              } else {
                current_http_channel = new HttpChannelWrapper(channelWrapper, closeAfterResponseCompletion)
                plain_handler.requestReceived(x.request, current_http_channel, AChunkedRequestStart) match {
                  case ResponseAction.AcceptChunking(None) => {
                    channelWrapper.closeChannel(false)
                    null
                  }
                  case ResponseAction.AcceptChunking(Some(h)) => {
                    chunked_handler = h
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
              if (chunked_handler == null) {
                //not in chunked mode currently
                channelWrapper.closeChannel(true)
                null

              } else {
                if (null == current_http_channel) {
                  //current request is completed already before every chunk received.
                  process(continue())
                } else {
                  chunked_handler.messageReceived(x)
                  process(continue())
                }
              }

            }
            case x: ChunkedMessageEnd => {
              if (chunked_handler == null) {
                //not in chunked mode currently
                channelWrapper.closeChannel(true)
                null

              } else {
                if (null == current_http_channel) {
                  //current request is completed already before every chunk received.
                  process(continue())
                } else {
                  chunked_handler.ended(x)
                  //TODO
                  //chunked_handler = null
                  process(continue())
                }
              }

            }
            case x: HttpRequest => {

              if (chunked_handler != null) {
                //i'm already in chunked mode.
                channelWrapper.closeChannel(true)
                null

              } else if (current_http_channel != null) {
                //the previous request's handlement is not finished.
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
                process(continue())

              } else {
                current_http_channel = new HttpChannelWrapper(channelWrapper, closeAfterResponseCompletion)
                val classifier = DynamicRequestClassifier(x)
                val action = plain_handler.requestReceived(x, current_http_channel, classifier)
                action match {
                  case ResponseAction.Normal => {
                    process(continue())
                  }
                  case ResponseAction.AcceptWebsocket(factory) => {
                    factory match {
                      case None => {
                        channelWrapper.closeChannel(true)
                        null
                      }
                      case Some(born) => {

                        //!!! optimization
                        original_parser.just_check_positions = true
                        try { continue() } catch { case FakeException => {} }
                        //!!!
                        original_parser.just_check_positions = false

                        val websocket_channel = new WebSocketChannelWrapper(channelWrapper)
                        val (websocket_channel_handler, wsframe_parser) = born(websocket_channel)

                        WebSocket13.tryAccept(x, Nil) match {

                          case WebSocketAcceptance.Ok(response) => {
                            current_http_channel.writeWebSocketResponse(response)
                            val websocket = new WebsocketTransformer(websocket_channel_handler, websocket_channel, wsframe_parser)

                            if (null != lastInput && lastInput.length > lastOffset) {
                              //DO NOT invoke websocket's bytesReceived here, or dead locks / too deep recursion will be found.
                              HandledResult(websocket, lastInput.drop(lastOffset).asByteBuffer)
                            } else {
                              HandledResult(websocket, null)
                            }
                          }
                          case WebSocketAcceptance.Failed(response) => {
                            current_http_channel.writeWebSocketResponse(response)
                            current_http_channel.channel.closeChannel(false)
                            null
                          }
                          case WebSocketAcceptance.ERROR => {
                            current_http_channel.channel.closeChannel(false)
                            null
                          }
                        }

                      }
                    }
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
          HandledResult(this, null)
        }
        case x => {
          channelWrapper.closeChannel(true)
          HandledResult(this, null)
        }
      }
    }

    process(result)
  }

  //TODO
  def writtenHappened(channelWrapper: ChannelWrapper): TrampledChannelHandler = {

    if (null == current_http_channel || (current_http_channel != null && current_http_channel.isCompleted)) {

      current_http_channel = null
      chunked_handler = null

    } else {

    }

    this
  }

  def channelIdled(channelWrapper: ChannelWrapper): Unit = {
    //TODO
  }

  def channelWritable(channelWrapper: ChannelWrapper): Unit = {
    //TODO
  }

  def inputEnded(channelWrapper: ChannelWrapper): Unit = {
    //TODO
  }

  def channelClosed(channelWrapper: ChannelWrapper, cause: ChannelClosedCause.Value, attachment: Option[_]): Unit = {
    //TODO

    head = null
    tail = null
    pipeline_size = 0
    //plain_handler = null
    current_http_channel = null
    //TODO
    //chunked_handler = null
    parser = null
  }
}