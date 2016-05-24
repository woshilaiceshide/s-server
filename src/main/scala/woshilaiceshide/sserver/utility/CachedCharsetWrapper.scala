package woshilaiceshide.sserver.utility

import java.nio.charset._

object CachedCharsetWrapper {

  def toArray(set: java.util.Set[String]) = {
    val arr = new Array[String](set.size)
    set.toArray(arr)
    arr
  }

}

import CachedCharsetWrapper._

class CachedCharsetWrapper(inner: Charset) extends Charset(inner.name(), toArray(inner.aliases())) {

  private val tl_decoder = new java.lang.ThreadLocal[CharsetDecoder]() {
    override def initialValue() = {
      inner.newDecoder() //.reset()
    }
  }

  private val tl_encoder = new java.lang.ThreadLocal[CharsetEncoder]() {
    override def initialValue() = {
      inner.newEncoder() //.reset()
    }
  }

  override def contains(cs: Charset): Boolean = {
    inner.contains(cs)
  }

  /**
   * according to sun jdk's source code, if null, then ThreadLocal.initialValue() will be invoked internally.
   */
  override def newDecoder(): CharsetDecoder = {
    //return inner.newDecoder()
    tl_decoder.get().reset()
  }

  /**
   * according to sun jdk's source code, if null, then ThreadLocal.initialValue() will be invoked internally.
   */
  override def newEncoder(): CharsetEncoder = {
    //return inner.newEncoder()
    tl_encoder.get().reset()
  }

  override def canEncode() = inner.canEncode()

  override def displayName(locale: java.util.Locale) = inner.displayName(locale)

  override def displayName() = inner.displayName()

}