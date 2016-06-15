package akka.http.impl.engine.sserver

import java.nio._
import akka.http.impl.util._

import akka.util.{ ByteStringBuilder, ByteString }

import woshilaiceshide.sserver.http.model._

private[http] class ByteArrayRendering(sizeHint: Int) extends Rendering {
  protected var array = new Array[Byte](sizeHint)
  protected var size = 0

  def get: Array[Byte] =
    if (size == array.length) array
    else java.util.Arrays.copyOfRange(array, 0, size)

  def ~~(char: Char): this.type = {
    val oldSize = growBy(1)
    array(oldSize) = char.toByte
    this
  }

  def ~~(bytes: Array[Byte]): this.type = {
    if (bytes.length > 0) {
      val oldSize = growBy(bytes.length)
      System.arraycopy(bytes, 0, array, oldSize, bytes.length)
    }
    this
  }

  def ~~(bytes: ByteString): this.type = {
    if (bytes.length > 0) {
      val oldSize = growBy(bytes.length)
      bytes.copyToArray(array, oldSize, bytes.length)
    }
    this
  }

  private def growBy(delta: Int): Int = {
    val oldSize = size
    val neededSize = oldSize.toLong + delta
    if (array.length < neededSize) {
      require(neededSize < Int.MaxValue, "Cannot create byte array greater than 2GB in size")
      val newLen = math.min(math.max(array.length.toLong << 1, neededSize), Int.MaxValue).toInt
      val newArray = new Array[Byte](newLen)
      System.arraycopy(array, 0, newArray, 0, array.length)
      array = newArray
    }
    size = neededSize.toInt
    oldSize
  }

  def ~~(data: HttpData): this.type = {
    if (data.nonEmpty) {
      if (data.length <= Int.MaxValue) {
        val oldSize = growBy(data.length.toInt)
        data.copyToArray(array, targetOffset = oldSize)
      } else sys.error("Cannot create byte array greater than 2GB in size")
    }
    this
  }
}

trait RichBytesRenderer[-T] {
  def render[R <: RichBytesRendering](r: R, value: T): r.type
}

trait RichBytesRendering extends Rendering {

  def reset(): Unit
  def to_byte_buffer(): ByteBuffer
  def ~~(bytes: Array[Byte], offset: Int, length: Int): this.type

  protected var original_start: Int = 0
  def set_original_start(i: Int) = this.original_start = i

  def render(r: RichBytesRendering): r.type

  def ~~(data: HttpData): this.type

  def ~~>[T](value: T)(implicit ev: RichBytesRenderer[T]): this.type = ev.render(this, value)

}

private[http] final class RichByteArrayRendering(sizeHint: Int) extends ByteArrayRendering(sizeHint) with RichBytesRendering {

  private val original_array = this.array

  override def reset() = {
    this.array = this.original_array
    this.size = original_start
    buffer.clear()
  }

  private val buffer = ByteBuffer.wrap(this.array)

  def to_byte_buffer(): ByteBuffer = {
    buffer.limit(this.size)
    buffer
  }

  private def growBy(delta: Int): Int = {
    val oldSize = size
    val neededSize = oldSize.toLong + delta
    if (array.length < neededSize)
      if (neededSize < Int.MaxValue) {
        val newLen = math.min(math.max(array.length.toLong * 2, neededSize), Int.MaxValue).toInt
        val newArray = new Array[Byte](newLen)
        System.arraycopy(array, 0, newArray, 0, array.length)
        array = newArray
      } else sys.error("Cannot create byte array greater than 2GB in size")
    size = neededSize.toInt
    oldSize
  }

  def ~~(bytes: Array[Byte], offset: Int, length: Int): this.type = {
    if (length > 0 && length + offset <= bytes.length) {
      val oldSize = growBy(length)
      System.arraycopy(bytes, offset, array, oldSize, length)
    }
    this
  }

  def render(r: RichBytesRendering): r.type = {
    r ~~ (this.array, 0, this.size)
  }
}

private[http] final class RichByteBufferRendering(sizeHint: Int) extends Rendering with RichBytesRendering {

  protected var original_buffer = ByteBuffer.allocateDirect(sizeHint)
  protected var buffer = original_buffer

  protected var flipped = false

  def get: Array[Byte] = {
    if (!flipped) {
      flipped = true
      buffer.flip()
    }
    buffer.mark()
    val bytes = new Array[Byte](buffer.remaining())
    buffer.get(bytes)
    buffer.reset()
    bytes
  }

  def reset(): Unit = {
    flipped = false
    buffer = original_buffer
    original_buffer.clear()
    original_buffer.position(original_start)
  }

  def to_byte_buffer(): ByteBuffer = {
    if (!flipped) {
      flipped = true
      buffer.flip()
    }
    buffer
  }

  def ~~(char: Char): this.type = {
    growBy(1)
    buffer.put(char.toByte)
    this
  }

  def ~~(bytes: Array[Byte]): this.type = {
    if (bytes.length > 0) {
      growBy(bytes.length)
      buffer.put(bytes, 0, bytes.length)
    }
    this
  }

  def ~~(bytes: Array[Byte], offset: Int, length: Int): this.type = {
    if (length > 0 && length + offset < bytes.length) {
      growBy(length)
      buffer.put(bytes, offset, length)
    }
    this
  }

  def ~~(bytes: ByteString): this.type = {
    if (bytes.length > 0) {
      val oldSize = growBy(bytes.length)
      bytes.copyToBuffer(buffer)
    }
    this
  }

  def render(r: RichBytesRendering): r.type = throw new java.lang.UnsupportedOperationException("")

  @scala.annotation.tailrec
  private def ~~(head: HttpData, tail: List[HttpData.NonEmpty]): Unit = {

    head match {
      case HttpData.Empty => {
        if (!tail.isEmpty) {
          ~~(tail.head, tail.tail)
        }
      }
      case HttpData.Bytes(bytes) => {
        bytes.copyToBuffer(buffer)
        if (!tail.isEmpty) {
          ~~(tail.head, tail.tail)
        }
      }
      case HttpData.FileBytes(fileName, offset, length) => {
        val file = new java.io.FileInputStream(fileName)
        try {
          val channel = file.getChannel()
          channel.position(offset)
          channel.read(buffer)
        } finally {
          file.close()
        }
        if (!tail.isEmpty) {
          ~~(tail.head, tail.tail)
        }
      }
      case HttpData.Compound(head1: HttpData.SimpleNonEmpty, tail1: HttpData.NonEmpty) => {
        ~~(head1, tail1 :: tail)
      }

    }

  }

  def ~~(data: HttpData): this.type = {
    if (data.nonEmpty) {
      if (data.length <= Int.MaxValue) {
        growBy(data.length.toInt)

        data match {
          case HttpData.Bytes(bytes) => {
            bytes.copyToBuffer(buffer)
          }
          case HttpData.FileBytes(fileName, offset, length) => {
            val file = new java.io.FileInputStream(fileName)
            try {
              val channel = file.getChannel()
              channel.position(offset)
              channel.read(buffer)
            } finally file.close()
          }
          case HttpData.Compound(head: HttpData.SimpleNonEmpty, tail: HttpData.NonEmpty) => {
            this ~~ (head, List(tail))
          }
          case HttpData.Empty => {}

        }

      } else sys.error("Cannot create byte array greater than 2GB in size")
    }
    this
  }

  private def growBy(delta: Int): Unit = {

    if (delta > buffer.remaining()) {
      val needed = buffer.capacity() + delta - buffer.remaining()
      if (needed < Int.MaxValue) {

        val tmp = ByteBuffer.allocate(needed)
        buffer.flip()
        tmp.put(buffer)
        buffer = tmp

      } else sys.error("Cannot create byte array greater than 2GB in size")
    }

  }
}