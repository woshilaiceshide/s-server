package woshilaiceshide.sserver.http

import akka.util.ByteString
import spray.util.SingletonException
import spray.http._

//public 'api's here

object HttpConfigurator {

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
    maxUriLength = 512,
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
}

final case class HttpConfigurator(
    parser_settings: spray.can.parsing.ParserSettings = HttpConfigurator.default_parser_settings,
    raw_request_uri_header: Boolean = false,
    max_request_in_pipeline: Int = 1,
    server_name: String = "S-SERVER/2.0-SNAPSHOT",
    chunkless_streaming: Boolean = false,
    transparent_header_requestes: Boolean = false,

    base_for_content_length_cache: Int = 1,
    size_for_content_length_cache: Int = 1024 * 4,

    /**
     * direct byte buffers will save memory copy when doing i/o.
     * but read/write direct byte buffers in jvm codes(not jni codes) would be slower in general.
     * and in different platforms and/or different jvms, things are different.
     *
     * so do your own test to determine this option's value.
     *
     * I just keep it false by default.
     *
     * for more information, please see http://stackoverflow.com/questions/5670862/bytebuffer-allocate-vs-bytebuffer-allocatedirect
     */
    use_direct_byte_buffer_for_cached_bytes_rendering: Boolean = false,
    /**
     * keep it large enough please. every thead that write http response will keep one cached render locally.
     */
    cached_bytes_rendering_length: Int = 1024,

    header_parser_pool_size: Int = 8,

    max_response_size: Int = 2048,
    max_payload_length_in_websocket_frame: Int = 2048,

    /**
     * when write http response, server and date headers should be written?
     *
     * its can be overridden using 'woshilaiceshide.sserver.http.HttpChannel.writeResponse(response: HttpResponsePart, sizeHint: Int, writeServerAndDateHeader: Boolean)'
     */
    write_server_and_date_headers: Boolean = false) {

  import woshilaiceshide.sserver.utility._

  private def get_rendering(size: Int) = {
    if (use_direct_byte_buffer_for_cached_bytes_rendering) {
      new ByteBufferRendering(size)
    } else {
      new RevisedByteArrayRendering(size)
    }
  }

  //a huge optimization. bytes copy and allocation is terrible in general.
  private val cached_bytes_rendering = new java.lang.ThreadLocal[RichBytesRendering]() {
    override def initialValue(): RichBytesRendering = {
      get_rendering(cached_bytes_rendering_length)
    }
  }

  private val cached_bytes_rendering_with_status_200 = new java.lang.ThreadLocal[RichBytesRendering]() {
    override def initialValue(): RichBytesRendering = {
      val tmp = get_rendering(cached_bytes_rendering_length)
      tmp ~~ RenderSupport.DefaultStatusLine
      tmp.set_original_start(RenderSupport.DefaultStatusLine.size)
      tmp
    }
  }

  //if (status eq StatusCodes.OK) r ~~ DefaultStatusLine else r ~~ StatusLineStart ~~ status ~~ CrLf
  /**
   * internal api. such interfaces in a framework should always be private because its usage requires more carefulness.
   */
  private[http] def borrow_bytes_rendering(size: Int, response_part: HttpResponsePart): RichBytesRendering = {
    if (size > cached_bytes_rendering_length) {
      val tmp = new RevisedByteArrayRendering(size)
      response_part match {
        case response: HttpResponse => {
          if (response.status eq StatusCodes.OK) tmp ~~ RenderSupport.DefaultStatusLine
          else tmp ~~ RenderSupport.StatusLineStart ~~ response.status ~~ RenderSupport.CrLf
        }
        case _ =>
      }
      tmp
    } else {

      response_part match {
        case response: HttpResponse => {
          if (response.status eq StatusCodes.OK) {
            var cached = cached_bytes_rendering_with_status_200.get()
            if (cached == null) {
              val tmp = get_rendering(cached_bytes_rendering_length)
              tmp ~~ RenderSupport.DefaultStatusLine
              tmp.set_original_start(RenderSupport.DefaultStatusLine.size)
              cached_bytes_rendering_with_status_200.set(tmp)
              cached = tmp
            }
            cached
          } else {
            var cached = cached_bytes_rendering.get()
            if (cached == null) {
              val tmp = get_rendering(cached_bytes_rendering_length)
              tmp ~~ RenderSupport.StatusLineStart ~~ response.status ~~ RenderSupport.CrLf
              cached_bytes_rendering.set(tmp)
              cached = tmp
            } else {
              cached ~~ RenderSupport.StatusLineStart ~~ response.status ~~ RenderSupport.CrLf
            }
            cached
          }
        }
        case _ => {
          var cached = cached_bytes_rendering.get()
          if (cached == null) {
            val tmp = get_rendering(cached_bytes_rendering_length)
            cached_bytes_rendering.set(tmp)
            cached = tmp
          }
          cached
        }
      }
    }
  }

  /**
   * internal api. such interfaces in a framework should always be private because its usage requires more carefulness.
   */
  private[http] def return_bytes_rendering(r: RichBytesRendering) = {
    r.reset()
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

  def get_request_parser(): HttpRequestPartParser = {
    new HttpRequestPartParser(parser_settings, raw_request_uri_header)(get_header_parser())
  }

  def get_websocket_parser(): WebSocket13.WSFrameParser = {
    WebSocket13.default_parser(max_payload_length_in_websocket_frame)
  }

  //if rendering has '~~(Array[Byte], offset: Int, length: Int)', 
  ///then this cache can be replaced by content_length_cache_with_two_crlf
  private val content_length_cache = {
    val cache = new Array[Array[Byte]](size_for_content_length_cache)
    for (i <- 0 until size_for_content_length_cache) {
      cache(i) = RenderSupport.`Content-Length-Bytes` ++ RenderSupport.getBytes(i + base_for_content_length_cache)
    }
    cache
  }

  private val content_length_cache_with_two_crlf = {
    val cache = new Array[Array[Byte]](size_for_content_length_cache)
    for (i <- 0 until size_for_content_length_cache) {
      cache(i) = RenderSupport.`Content-Length-Bytes` ++ RenderSupport.getBytes(i + base_for_content_length_cache) ++ RenderSupport.TwoCrLf
    }
    cache
  }

  def render_content_length(r: Rendering, length: Long, with_two_crlf: Boolean): Unit = {
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
    } else {
      r ~~ RenderSupport.`Content-Length-Bytes` ~~ length
      if (with_two_crlf) r ~~ RenderSupport.TwoCrLf
    }
  }

}

