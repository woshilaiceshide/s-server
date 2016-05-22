package woshilaiceshide.sserver

import akka.util.ByteString
import spray.util.SingletonException
import spray.http._

package object http {

  type Parser = ByteString ⇒ Result

  sealed trait Result
  object Result {
    final case class NeedMoreData(next: Parser) extends Result
    sealed trait AbstractEmit extends Result {
      val part: HttpMessagePart
      val closeAfterResponseCompletion: Boolean
      def continue: Result
    }
    final case class EmitLazily(part: HttpMessagePart, closeAfterResponseCompletion: Boolean, lazy_continue: () ⇒ Result) extends AbstractEmit {
      def continue = lazy_continue()
    }
    //no lazy evaluation. this optimization is proved by facts.
    final case class EmitDirectly(part: HttpMessagePart, closeAfterResponseCompletion: Boolean, continue: Result) extends AbstractEmit
    final case class Expect100Continue(continue: () ⇒ Result) extends Result
    final case class ParsingError(status: StatusCode, info: ErrorInfo) extends Result
    case object IgnoreAllFurtherInput extends Result with Parser { def apply(data: ByteString) = this }
  }

  class ParsingException(val status: StatusCode, val info: ErrorInfo) extends RuntimeException(info.formatPretty) {
    def this(status: StatusCode, summary: String = "") =
      this(status, ErrorInfo(if (summary.isEmpty) status.defaultMessage else summary))
    def this(summary: String) =
      this(StatusCodes.BadRequest, ErrorInfo(summary))
  }

  object NotEnoughDataException extends SingletonException

  val headerValueCacheLimits = Map(
    "default" -> 12,
    "Content-MD5" -> 0,
    "Date" -> 0,
    "If-Match" -> 0,
    "If-Modified-Since" -> 0,
    "If-None-Match" -> 0,
    "If-Range" -> 0,
    "If-Unmodified-Since" -> 0,
    "User-Agent" -> 32)

  val default_parser_settings = spray.can.parsing.ParserSettings(
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

  case class HttpConfigurator(
      parser_settings: spray.can.parsing.ParserSettings = default_parser_settings,
      raw_request_uri_header: Boolean = false,
      max_request_in_pipeline: Int = 1,
      server_name: String = "S-SERVER/2.0-SNAPSHOT",
      chunkless_streaming: Boolean = false,
      transparent_header_requestes: Boolean = false,

      base_for_content_length_cache: Int = 1,
      size_for_content_length_cache: Int = 1024 * 4,

      bytes_rendering_pool_size: Int = 8,
      bytes_rendering_length_in_pool: Int = 1024,

      header_parser_pool_size: Int = 8,

      max_response_size: Int = 2048,
      max_payload_length_in_websocket_frame: Int = 2048) {

    import woshilaiceshide.sserver.utility._

    //a huge optimization. bytes copy and allocation is terrible in general.
    private val tl_r_pool = new java.lang.ThreadLocal[ArrayNodeStack[RevisedByteArrayRendering]]() {
      override def initialValue(): ArrayNodeStack[RevisedByteArrayRendering] = {
        val pool = new ArrayNodeStack[RevisedByteArrayRendering](bytes_rendering_pool_size)
        for (i <- 0 until bytes_rendering_pool_size) {
          pool.push(new RevisedByteArrayRendering(bytes_rendering_length_in_pool))
        }
        pool
      }
    }

    def borrow_bytes_rendering(size: Int) = {
      if (size > bytes_rendering_length_in_pool) {
        new Revised1ByteArrayRendering(size, 0)
      } else {
        val pool = tl_r_pool.get
        val poped = pool.pop()
        if (poped.isEmpty) {
          new Revised1ByteArrayRendering(size, 0)
        } else {
          poped.get
        }
      }
    }

    def return_bytes_rendering(r: RevisedByteArrayRendering) = {
      r match {
        case x: Revised1ByteArrayRendering => {}
        case x => {
          x.reset()
          tl_r_pool.get.push(x)
        }
      }
    }

    //a huge optimization. header parser has a trie, which consumes lots of cpu.
    private val tl_header_parser = new java.lang.ThreadLocal[HttpHeaderParser]() {
      override def initialValue(): HttpHeaderParser = {
        HttpHeaderParser(parser_settings)
      }
    }

    private def get_header_parser() = {
      var tmp = tl_header_parser.get
      if (null == tmp) {
        tmp = HttpHeaderParser(parser_settings)
        tl_header_parser.set(tmp)
      }
      tmp
    }

    def get_request_parser(): HttpTransformer.RevisedHttpRequestPartParser = {
      new HttpTransformer.RevisedHttpRequestPartParser(parser_settings, raw_request_uri_header, get_header_parser())
    }

    def get_websocket_parser(): WebSocket13.WSFrameParser = {
      WebSocket13.default_parser(max_payload_length_in_websocket_frame)
    }

    import spray.util._

    private def getBytes(renderable: Renderable, sizeHint: Int = 512) = {
      val r = new ByteArrayRendering(sizeHint)
      r ~~ renderable
      r.get
    }

    private def getBytes(l: Long) = {
      val r = new ByteArrayRendering(64)
      r ~~ l
      r.get
    }

    //if rendering has '~~(Array[Byte], offset: Int, length: Int)', 
    ///then this cache can be replaced by content_length_cache_with_two_crlf
    private val content_length_cache = {
      val cache = new Array[Array[Byte]](size_for_content_length_cache)
      for (i <- 0 until size_for_content_length_cache) {
        cache(i) = "Content-Length: ".getAsciiBytes ++ getBytes(i + base_for_content_length_cache)
      }
      cache
    }

    private val content_length_cache_with_two_crlf = {
      val cache = new Array[Array[Byte]](size_for_content_length_cache)
      val two_crlf = getBytes(Rendering.CrLf) ++ getBytes(Rendering.CrLf)
      for (i <- 0 until size_for_content_length_cache) {
        cache(i) = "Content-Length: ".getAsciiBytes ++ getBytes(i + base_for_content_length_cache) ++ two_crlf
      }
      cache
    }

    def render_content_length(r: Rendering, length: Long, with_two_crlf: Boolean) = {
      if (base_for_content_length_cache <= length &&
        length < base_for_content_length_cache + content_length_cache.length &&
        length < Integer.MAX_VALUE) {

        if (with_two_crlf) {
          val bytes = content_length_cache_with_two_crlf(length.toInt - base_for_content_length_cache)
          r ~~ bytes
        } else {
          val bytes = content_length_cache(length.toInt - base_for_content_length_cache)
          r ~~ bytes
        }
        true

      } else {
        false
      }
    }

  }

}