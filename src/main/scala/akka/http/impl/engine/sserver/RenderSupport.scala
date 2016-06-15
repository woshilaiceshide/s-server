/*
 * Copyright © 2011-2015 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.impl.engine.sserver

import akka.http.impl.util._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._

import woshilaiceshide.sserver.http.model._

import scala.annotation._

/**
 * DO NOT modify my fields. I'm not 'private' from now on.
 */
object RenderSupport {
  val DefaultStatusLine = "HTTP/1.1 200 OK\r\n".asciiBytes
  val StatusLineStart = "HTTP/1.1 ".asciiBytes
  val Chunked = "chunked".asciiBytes
  val KeepAlive = "Keep-Alive".asciiBytes
  val Close = "close".asciiBytes
  val Upgrade = "Upgrade".asciiBytes

  def getBytes(renderable: Renderable, sizeHint: Int = 512) = {
    val r = new ByteArrayRendering(sizeHint)
    r ~~ renderable
    r.get
  }

  def getBytes(l: Long) = {
    val r = new ByteArrayRendering(64)
    r ~~ l
    r.get
  }
  def getBytes(s: String) = {
    s.asciiBytes
  }

  val CrLf = getBytes(Rendering.CrLf)
  val TwoCrLf = getBytes(Rendering.CrLf) ++ getBytes(Rendering.CrLf)

  val `Content-Type-Bytes` = "Content-Type: ".asciiBytes
  //TODO for more common content types
  val `Content-Type--text/plain(UTF-8)-Bytes` = getBytes(`Content-Type`(ContentTypes.`text/plain(UTF-8)`))
  val `Content-Type--text/plain(UTF-8)-CrLf-Bytes` = getBytes(`Content-Type`(ContentTypes.`text/plain(UTF-8)`)) ++ CrLf
  //TODO 
  //val `Content-Type--text/plain-Bytes` = getBytes(`Content-Type`(ContentTypes.`text/plain`))
  //val `Content-Type--text/plain-CrLf-Bytes` = getBytes(`Content-Type`(ContentTypes.`text/plain`)) ++ CrLf
  val `Content-Length-Bytes` = "Content-Length: ".asciiBytes

  val `Connection: KeepAlive-CrLf` = getBytes(Connection) ++ ": ".asciiBytes ++ KeepAlive ++ CrLf
  val `Connection: Close-CrLf` = getBytes(Connection) ++ ": ".asciiBytes ++ Close ++ CrLf

  val `Transfer-Encoding: Chunked-TwoCrLf` = getBytes(`Transfer-Encoding`) ++ ": ".asciiBytes ++ Chunked ++ TwoCrLf

  implicit object MessageChunkRenderer extends RichBytesRenderer[MessageChunk] {
    def render[R <: RichBytesRendering](r: R, chunk: MessageChunk): r.type = {
      import chunk._
      r ~~% data.length
      if (!extension.isEmpty) r ~~ ';' ~~ extension
      r ~~ CrLf ~~ data ~~ CrLf
    }
  }

  implicit object ChunkedMessageEndRenderer extends Renderer[ChunkedMessageEnd] {
    implicit val trailerRenderer = Renderer.genericSeqRenderer[Renderable, HttpHeader](Rendering.CrLf, Rendering.Empty)
    def render[R <: Rendering](r: R, part: ChunkedMessageEnd): r.type = {
      r ~~ '0'
      if (!part.extension.isEmpty) r ~~ ';' ~~ part.extension
      r ~~ CrLf
      if (!part.trailer.isEmpty) r ~~ part.trailer ~~ CrLf
      r ~~ CrLf
    }
  }
}
