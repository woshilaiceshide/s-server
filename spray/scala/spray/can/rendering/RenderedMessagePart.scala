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

package spray.can.rendering

import spray.http._

private[can] case class RequestPartRenderingContext(
  requestPart: HttpRequestPart,
  requestProtocol: HttpProtocol = HttpProtocols.`HTTP/1.1`)

private[can] case class ResponsePartRenderingContext(
  responsePart: HttpResponsePart,
  requestMethod: HttpMethod = HttpMethods.GET,
  requestProtocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
  closeAfterResponseCompletion: Boolean = false)
