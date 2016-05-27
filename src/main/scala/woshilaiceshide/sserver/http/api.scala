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

    bytes_rendering_pool_size: Int = 2,
    bytes_rendering_length_in_pool: Int = 1024,

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
  private val tl_r_pool_with_status_200 = new java.lang.ThreadLocal[ArrayNodeStack[RevisedByteArrayRendering]]() {
    override def initialValue(): ArrayNodeStack[RevisedByteArrayRendering] = {
      val pool = new ArrayNodeStack[RevisedByteArrayRendering](bytes_rendering_pool_size)
      for (i <- 0 until bytes_rendering_pool_size) {
        val tmp = new Revised2ByteArrayRendering(bytes_rendering_length_in_pool)
        tmp ~~ RenderSupport.DefaultStatusLine
        tmp.set_original_start(RenderSupport.DefaultStatusLine.size)
        pool.push(tmp)
      }
      pool
    }
  }

  //if (status eq StatusCodes.OK) r ~~ DefaultStatusLine else r ~~ StatusLineStart ~~ status ~~ CrLf
  /**
   * internal api. such interfaces in a framework should always be private because its usage requires more carefulness.
   */
  private[http] def borrow_bytes_rendering(size: Int, response_part: HttpResponsePart) = {
    if (size > bytes_rendering_length_in_pool) {
      val tmp = new Revised1ByteArrayRendering(size)
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
            val poped = tl_r_pool_with_status_200.get().pop()
            if (poped.isEmpty) {
              val tmp = new Revised1ByteArrayRendering(size)
              tmp ~~ RenderSupport.DefaultStatusLine
              tmp
            } else {
              poped.get
            }
          } else {
            val poped = tl_r_pool.get().pop()
            if (poped.isEmpty) {
              val tmp = new Revised1ByteArrayRendering(size)
              tmp ~~ RenderSupport.StatusLineStart ~~ response.status ~~ RenderSupport.CrLf
              tmp
            } else {
              poped.get ~~ RenderSupport.StatusLineStart ~~ response.status ~~ RenderSupport.CrLf
            }
          }
        }
        case _ => {
          val poped = tl_r_pool.get().pop()
          if (poped.isEmpty) {
            new Revised1ByteArrayRendering(size)
          } else {
            poped.get
          }
        }
      }
    }
  }

  /**
   * internal api. such interfaces in a framework should always be private because its usage requires more carefulness.
   */
  private[http] def return_bytes_rendering(r: RevisedByteArrayRendering) = {
    r match {
      case x: Revised1ByteArrayRendering => {}
      case x: Revised2ByteArrayRendering => {
        x.reset()
        tl_r_pool_with_status_200.get.push(x)
      }
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
