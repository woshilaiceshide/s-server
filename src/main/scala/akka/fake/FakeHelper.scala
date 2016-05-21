package akka.fake

import java.nio._

object FakeHelper {

  def byte_string_from_byte_buffer_directly(buffer: ByteBuffer) = {
    akka.util.ByteString.ByteString1.apply(buffer.array(), buffer.position(), buffer.limit())

  }

  def byte_string_from_byte_array_directly(bytes: Array[Byte], offset: Int, length: Int) = {

    akka.util.ByteString.ByteString1.apply(bytes, offset, length)

  }

}