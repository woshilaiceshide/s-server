package woshilaiceshide.sserver.utility

import java.nio.ByteBuffer

trait Utility {

  def toBytesArray(byteBuffer: ByteBuffer) = {
    val count = byteBuffer.remaining()
    val bytes = new Array[Byte](count)
    byteBuffer.get(bytes)
    bytes
  }

  def closeIfFailed(c: java.io.Closeable)(task: => Unit) = {
    try {
      task
    } catch {
      case ex: Throwable => {
        c.close()
        throw ex
      }
    }

  }

}

object Utility extends Utility