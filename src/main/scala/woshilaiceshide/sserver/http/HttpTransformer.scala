package woshilaiceshide.sserver.http

import akka.util._

import woshilaiceshide.sserver.http._

import spray.can.parsing._
import spray.http._

import woshilaiceshide.sserver.nio._

object HttpTransformer {

  val log = org.slf4j.LoggerFactory.getLogger(classOf[HttpTransformer]);

  private[HttpTransformer] final case class Node(value: HttpRequestPart, closeAfterResponseCompletion: Boolean, channelWrapper: ChannelWrapper, var next: Node)

  final class MyChannelInformation(channel: HttpChannel) extends ChannelInformation {
    def remoteAddress = channel.remoteAddress
    def localAddress = channel.localAddress
  }
}

/**
 * transform bytes to http.
 * when counting messages in the pipeline(see "http pipelining"), i take every chunked message into account as intended.
 *
 * to limit the un-processed bytes(related to the messages in pipeline), use 'woshilaiceshide.sserver.http.HttpConfigurator' please.
 *
 * this class is not reusable.
 */
class HttpTransformer(handler: HttpChannelHandler, configurator: HttpConfigurator)
    extends ChannelHandler {

  import HttpTransformer._

  private var original_parser: S2HttpRequestPartParser = null
  private var parser: Parser = null

  private[this] var current_sink: ResponseSink = null
  private[this] var current_http_channel: HttpChannel = _

  //get parser from ThreadLocal here! so that all the codes related to parser is in the same thread.
  def channelOpened(channelWrapper: ChannelWrapper): Unit = {
    original_parser = configurator.get_request_parser()
    parser = original_parser
  }

  private var head: Node = null
  private var tail: Node = null
  private var pipeline_size = 0
  @inline private def has_next(): Boolean = null != head

  @inline private def en_queue(request: HttpRequestPart, closeAfterResponseCompletion: Boolean, channelWrapper: ChannelWrapper) = {
    if (null == head) {
      head = Node(request, closeAfterResponseCompletion, channelWrapper, null)
      tail = head
      pipeline_size = 1
    } else {
      if (pipeline_size >= configurator.max_request_in_pipeline) {
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
      if (null == head) tail = null
      pipeline_size = pipeline_size - 1
      tmp
    }
  }
  @inline private def clear_queue() = {
    head = null
    tail = null
    pipeline_size = 0
  }
  @inline private def is_pipeling_queue_empty() = pipeline_size == 0

  private var cachable: Cachable = null
  private var prev_response: ResponseAction.ResponseWithThis = null
  private var prev_http_channel: HttpChannel = null
  private def has_cached: Boolean = null != cachable || null != prev_response

  //https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/package-summary.html#MemoryVisibility
  private[http] def flush() = {
    val r = if (null != cachable) {
      cachable.flush()
      cachable = null
    } else {
      prev_http_channel.writeResponse(prev_response.response, prev_response.size_hint, prev_response.write_server_and_date_headers)
      prev_http_channel = null
      prev_response = null
    }
    r
  }
  private def cache_response(ht: HttpChannel, rwt: ResponseAction.ResponseWithThis) = {
    if (null == cachable && null == prev_response) {
      prev_response = rwt
      prev_http_channel = ht
    } else {
      if (null == cachable) {
        cachable = ht.channel.cachable(rwt.size_hint)
      }
      var breaked = false
      if (prev_response != null) {
        val wr = prev_http_channel.writeResponseToCache(prev_response.response, prev_response.size_hint, prev_response.write_server_and_date_headers, cachable)
        if (wr == WriteResult.WR_OK_BUT_OVERFLOWED && prev_response.close_if_overflowed) {
          ht.channel.closeChannel(false)
          breaked = true
        }
        prev_response = null
        prev_http_channel = null
      }
      if (!breaked) {
        val wr = ht.writeResponseToCache(rwt.response, rwt.size_hint, rwt.write_server_and_date_headers, cachable)
        if (wr == WriteResult.WR_OK_BUT_OVERFLOWED && rwt.close_if_overflowed) {
          ht.channel.closeChannel(false)
        }
      }
    }
  }

  def bytesReceived(byteBuffer: java.nio.ByteBuffer, channelWrapper: ChannelWrapper): ChannelHandler = {

    //a reasonable request flow is produced
    //val result = parser.apply(ByteString.apply(byteBuffer))
    val result = parser.apply(akka.spray.createByteStringUnsafe(byteBuffer))

    var cache_status = 0
    def cache_touched() = cache_status = cache_status | 1
    def cache_channel_maybe_leaked() = cache_status = cache_status | 2
    def cache_flushed() = cache_status = 0
    def flush_cache() = {
      if (cache_status == (1 | 2)) {
        HttpTransformer.this.synchronized { flush() }
        cache_status = 0
      } else if (cache_status == 1) {
        flush()
        cache_status = 0
      }
    }

    @scala.annotation.tailrec def process(result: Result): ChannelHandler = {
      //TODO if channel is closed because "Keep-Alive" is false(or other causes), exit as early as possible
      val clear = if (null == current_http_channel) {
        true
      } else if (current_http_channel.isCompleted) {
        //null-ed it as early as possible
        current_http_channel = null
        cache_flushed()
        true
      } else {
        false
      }

      result match {
        //closeAfterResponseCompletion will be fine even if it's a 'MessageChunk'
        case r: Result.AbstractEmit /*if (r.part.isInstanceOf[HttpRequestPart])*/ /*it's HttpRequestPart definitely.*/ => {

          import r._
          val request = r.part.asInstanceOf[HttpRequestPart]

          request match {
            case x: HttpRequest => {

              if (clear) {
                if (current_sink != null) {
                  current_sink.channelClosed()
                  current_sink = null
                }

                current_http_channel = new HttpChannel(channelWrapper, closeAfterResponseCompletion, this, has_cached, x.method, x.protocol, configurator)
                val action = handler.requestReceived(x, current_http_channel, DynamicRequestClassifier)
                action match {
                  case x: ResponseAction.ResponseWithThis => {
                    cache_response(current_http_channel, x)
                    //completed
                    current_http_channel = null
                    cache_touched()
                    process(continue)
                  }
                  case ResponseAction.ResponseByMyself => {
                    if (current_http_channel.isCompleted) {
                      //null-ed it as early as possible
                      current_http_channel = null
                      cache_flushed()
                    } else {
                      cache_channel_maybe_leaked()
                    }
                    //pipeline may be OK, even if http pipeline is disabled. 
                    process(continue)
                  }
                  case ResponseAction.AcceptWebsocket(factory) => {
                    flush_cache()
                    val websocket_channel = new WebSocketChannel(channelWrapper, closeAfterResponseCompletion, x.method, x.protocol, configurator)
                    val websocket_channel_handler = factory(websocket_channel)
                    val websocket = new WebsocketTransformer(websocket_channel_handler, websocket_channel, configurator)

                    //1. no remaining data should be here because handshake does not complete. i does not check it here yet.
                    //2. DO NOT invoke websocket's bytesReceived here, or dead locks / too deep recursion may be found.
                    //websocket.bytesReceived(r.remainingInput.drop(r.remainingOffset).asByteBuffer, channelWrapper)
                    if (original_parser.remainingCount > 0) {
                      throw new RuntimeException("no data should be here because handshake does not complete.")
                    }

                    if (!websocket_channel.is_accepted) {
                      throw new RuntimeException("please accept this websocket first using 'woshilaiceshide.sserver.http.WebSocketChannel.tryAccept(request: HttpRequest, extraHeaders: List[HttpHeader], cookies: List[HttpCookie])'")
                    }

                    websocket
                  }
                  case ResponseAction.ResponseWithASink(sink) => {
                    cache_channel_maybe_leaked()
                    current_sink = sink
                    process(continue)
                  }
                  case _ => {
                    flush_cache()
                    channelWrapper.closeChannel(false)
                    this
                  }
                }

              } else {
                en_queue(x, closeAfterResponseCompletion, channelWrapper)
                process(continue)

              }
            }
            case x: MessageChunk => {
              if (head == null) {
                if (current_sink != null) {
                  current_sink match {
                    case c: ChunkedRequestHandler => {
                      c.chunkReceived(x)
                      process(continue)
                    }
                    case _ => {
                      channelWrapper.closeChannel(true)
                      //do not make it null now!!!
                      //current_sink = null
                      this
                    }
                  }
                } else {
                  process(continue)
                }
              } else {
                en_queue(x, closeAfterResponseCompletion, channelWrapper)
                process(continue)
              }
            }
            case x: ChunkedRequestStart => {
              if (current_http_channel != null) {
                en_queue(x, closeAfterResponseCompletion, channelWrapper)
                process(continue)
              } else {
                if (current_sink != null) {
                  current_sink = null
                }

                current_http_channel = new HttpChannel(channelWrapper, closeAfterResponseCompletion, this, has_cached, x.request.method, x.request.protocol, configurator)
                val action = handler.requestReceived(x.request, current_http_channel, AChunkedRequestStart)
                action match {
                  case ResponseAction.AcceptChunking(h) => {
                    cache_channel_maybe_leaked()
                    current_sink = h
                    process(continue)
                  }
                  case x: ResponseAction.ResponseWithThis => {
                    cache_response(current_http_channel, x)
                    //completed
                    current_http_channel = null
                    cache_touched()
                    process(continue)
                  }
                  case ResponseAction.ResponseByMyself => {
                    cache_channel_maybe_leaked()
                    if (current_http_channel.isCompleted) {
                      //null-ed it as early as possible
                      current_http_channel = null
                    }
                    process(continue)
                  }
                  case _ => {
                    flush_cache()
                    channelWrapper.closeChannel(false)
                    this
                  }
                }

              }
            }
            case x: ChunkedMessageEnd => {
              if (head == null) {
                if (current_sink != null) {
                  current_sink match {
                    case c: ChunkedRequestHandler => {
                      c.chunkEnded(x)
                      process(continue)
                    }
                    case _ => {
                      channelWrapper.closeChannel(true)
                      //do not make it null now!!!
                      //current_sink = null
                      this
                    }
                  }
                } else {
                  process(continue)
                }

              } else {
                en_queue(x, closeAfterResponseCompletion, channelWrapper)
                process(continue)
              }
            }
          }
        }
        case Result.NeedMoreData(parser1) => {
          parser = parser1
          this
        }
        case Result.ParsingError(status, info) => {
          log.debug(s"""parsing error(${status}), ${info.formatPretty}""")
          channelWrapper.closeChannel(true)
          this
        }
        case Result.IgnoreAllFurtherInput => {
          channelWrapper.closeChannel(true)
          this
        }
        case Result.Expect100Continue(_) => {
          log.debug(s"""Expect  = "100-continue" is not supported.""")
          channelWrapper.closeChannel(true)
          this
        }
      }
    }

    val tmp = process(result)
    flush_cache()
    original_parser.shadow()
    tmp
  }

  //invoked in the i/o thread, which is not reading sockets currently.
  @scala.annotation.tailrec
  final private def process_request_in_pipeline(next: Node, channelWrapper: ChannelWrapper): ChannelHandler = {

    val request = next.value
    val closeAfterResponseCompletion = next.closeAfterResponseCompletion
    next.value match {

      case x: HttpRequest => {
        current_http_channel = new HttpChannel(channelWrapper, closeAfterResponseCompletion, this, has_cached, x.method, x.protocol, configurator)
        val action = handler.requestReceived(x, current_http_channel, DynamicRequestClassifier)
        action match {
          case x: ResponseAction.ResponseWithThis => {
            current_http_channel.writeResponse(x.response, x.size_hint, x.write_server_and_date_headers)
            //completed
            current_http_channel = null
            this
          }
          case ResponseAction.ResponseByMyself => {
            if (current_http_channel.isCompleted) {
              //null-ed it as early as possible
              current_http_channel = null
            }
            this
          }
          case ResponseAction.AcceptWebsocket(factory) => {

            val websocket_channel = new WebSocketChannel(channelWrapper, closeAfterResponseCompletion, x.method, x.protocol, configurator)
            val websocket_channel_handler = factory(websocket_channel)
            val websocket = new WebsocketTransformer(websocket_channel_handler, websocket_channel, configurator)

            if (!is_pipeling_queue_empty()) {
              //DO NOT invoke websocket's bytesReceived here, or dead locks / too deep recursion will be found.
              //websocket.bytesReceived(lastInput.drop(lastOffset).asByteBuffer, channelWrapper)
              throw new RuntimeException("no data should be here because handshake does not complete.")
            }
            if (!websocket_channel.is_accepted) {
              throw new RuntimeException("please accept this websocket first using 'woshilaiceshide.sserver.http.WebSocketChannel.tryAccept(request: HttpRequest, extraHeaders: List[HttpHeader], cookies: List[HttpCookie])'")
            }
            websocket
          }
          case ResponseAction.ResponseWithASink(sink) => {
            current_sink = sink
            this
          }
          case _ => {
            channelWrapper.closeChannel(false)
            this
          }
        }

      }

      case x: MessageChunk => {
        if (current_sink != null) {
          current_sink match {
            case c: ChunkedRequestHandler => {
              c.chunkReceived(x)
              val next = de_queue()
              if (null != next) {
                process_request_in_pipeline(next, channelWrapper)
              } else {
                this
              }
            }
            case _ => {
              channelWrapper.closeChannel(true)
              //do not make it null now!!!
              //current_sink = null
              this
            }
          }
        } else {
          this
        }
      }

      case x: ChunkedRequestStart => {

        current_http_channel = new HttpChannel(channelWrapper, closeAfterResponseCompletion, this, has_cached, x.request.method, x.request.protocol, configurator)
        val action = handler.requestReceived(x.request, current_http_channel, AChunkedRequestStart)
        action match {
          case ResponseAction.AcceptChunking(h) => {
            current_sink = h
            this
          }
          case x: ResponseAction.ResponseWithThis => {
            current_http_channel.writeResponse(x.response, x.size_hint, x.write_server_and_date_headers)
            //completed
            current_http_channel = null
            this
          }
          case ResponseAction.ResponseByMyself => {
            if (current_http_channel.isCompleted) {
              //null-ed it as early as possible
              current_http_channel = null
            }
            this
          }
          case _ => {
            channelWrapper.closeChannel(false)
            this
          }
        }

      }

      case x: ChunkedMessageEnd => {
        if (current_sink != null) {
          current_sink match {
            case c: ChunkedRequestHandler => {
              c.chunkEnded(x)
              val next = de_queue()
              if (null != next) {
                process_request_in_pipeline(next, channelWrapper)
              } else {
                this
              }
            }
            case _ => {
              channelWrapper.closeChannel(true)
              //do not make it null now!!!
              //current_sink = null
              this
            }
          }
        } else {
          this
        }
      }
    }

  }

  private[http] def process_request_in_pipeline(channelWrapper: ChannelWrapper): Unit = {

    if (null == current_http_channel || current_http_channel.isCompleted) {

      current_http_channel = null
      if (null != current_sink) {
        current_sink.channelClosed()
        current_sink = null
      }

      val next = de_queue()
      if (null != next) {
        process_request_in_pipeline(next, channelWrapper)
      }
    }

  }

  def channelIdled(channelWrapper: ChannelWrapper): Unit = {
    if (null != current_sink) current_sink.channelIdled()
  }

  def channelWritable(channelWrapper: ChannelWrapper): Unit = {
    if (null != current_sink) current_sink.channelWritable()
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