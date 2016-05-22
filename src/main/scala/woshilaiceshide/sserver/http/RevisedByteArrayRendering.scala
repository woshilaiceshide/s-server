package woshilaiceshide.sserver.http

private[http] class RevisedByteArrayRendering(sizeHint: Int) extends spray.http.ByteArrayRendering(sizeHint) {

  def get_underlying_array() = this.array
  def get_underlying_offset() = 0
  def get_underlying_size() = this.size
  def reset() = { this.size = 0 }
}

private[http] class Revised1ByteArrayRendering(sizeHint: Int) extends RevisedByteArrayRendering(sizeHint) {
  override def reset() = { this.size = 0 }
}

private[http] class Revised2ByteArrayRendering(sizeHint: Int) extends RevisedByteArrayRendering(sizeHint) {

  override def reset() = { this.size = original_start }

  private var original_start: Int = 0
  def set_original_start(i: Int) = this.original_start = i

}