package woshilaiceshide.sserver.utility

import java.nio.ByteBuffer

trait Utility {

  def toBytesArray(byteBuffer: ByteBuffer) = {
    val count = byteBuffer.remaining()
    val bytes = new Array[Byte](count)
    byteBuffer.get(bytes)
    bytes
  }

}

object Utility extends Utility