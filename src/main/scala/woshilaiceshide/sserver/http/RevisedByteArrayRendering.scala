package woshilaiceshide.sserver.http

class RevisedByteArrayRendering(sizeHint: Int) extends spray.http.ByteArrayRendering(sizeHint) {

  def get_underlying_array() = this.array
  def get_underlying_offset() = 0
  def get_underlying_size() = this.size
  def reset() = { this.size = 0 }

}

class Revised1ByteArrayRendering(sizeHint: Int, val par: Int) extends RevisedByteArrayRendering(sizeHint) 