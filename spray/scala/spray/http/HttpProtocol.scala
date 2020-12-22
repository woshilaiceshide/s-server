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

package spray.http

final case class HttpProtocol private (value: String) extends LazyValueBytesRenderable

object HttpProtocols extends ObjectRegistry[String, HttpProtocol] {
  private def register(p: HttpProtocol): HttpProtocol = register(p.value, p)

  val `HTTP/1.0` = register(HttpProtocol("HTTP/1.0"))
  val `HTTP/1.1` = register(HttpProtocol("HTTP/1.1"))
}
