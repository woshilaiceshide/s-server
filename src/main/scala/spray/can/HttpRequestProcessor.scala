package spray.can

import _root_.spray.http._

trait HttpRequestProcessor {

  def channelWrapper: HttpChannelWrapper

  def chunkReceived(x: MessageChunk): Unit = {}
  def chunkEnded(x: ChunkedMessageEnd): Unit = {}

  //I's completed just now if I return true!
  def channelWritable(): Boolean
  def close(): Unit
  def finished: Boolean
}