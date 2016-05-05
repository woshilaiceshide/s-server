package woshilaiceshide.sserver.http

import scala.annotation.tailrec
import akka.util.ByteString
import spray.http._
import HttpHeaders._
import CharUtils._
import ProtectedHeaderCreation.enable

object SpecializedHeaderValueParsers {
  import HttpHeaderParser._

  def specializedHeaderValueParsers = Seq(ContentLengthParser)

  object ContentLengthParser extends HeaderValueParser("Content-Length", maxValueCount = 1) {
    def apply(input: ByteString, valueStart: Int, warnOnIllegalHeader: ErrorInfo ⇒ Unit): (HttpHeader, Int) = {
      @tailrec def recurse(ix: Int = valueStart, result: Long = 0): (HttpHeader, Int) = {
        val c = byteChar(input, ix)
        if (isDigit(c) && result >= 0) recurse(ix + 1, result * 10 + c - '0')
        else if (isWhitespace(c)) recurse(ix + 1, result)
        else if (c == '\r' && byteChar(input, ix + 1) == '\n' && result >= 0) (`Content-Length`(result), ix + 2)
        else fail("Illegal `Content-Length` header value")
      }
      recurse()
    }
  }
}