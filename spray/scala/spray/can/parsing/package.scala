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

package spray.can

import akka.util.ByteString
import spray.util.SingletonException
import spray.http._

package object parsing {
  type Parser = ByteString ⇒ Result

  def byteChar(input: ByteString, ix: Int): Char =
    if (ix < input.length) input(ix).toChar else throw NotEnoughDataException

  def validateNextTwoChars(input: ByteString, ix: Int, c0: Char, c1: Char): Boolean = {
    if (ix + 1 < input.length)
      input(ix).toChar == c0 && input(ix + 1).toChar == c1
    else throw NotEnoughDataException
  }

}

package parsing {

  sealed trait Result
  object Result {
    final case class NeedMoreData(next: Parser) extends Result
    sealed trait AbstractEmit extends Result {
      val part: HttpMessagePart
      val closeAfterResponseCompletion: Boolean
      def continue: Result
    }
    final case class EmitLazily(part: HttpMessagePart, closeAfterResponseCompletion: Boolean, lazy_continue: () ⇒ Result) extends AbstractEmit {
      throw new scala.NotImplementedError()
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

}

