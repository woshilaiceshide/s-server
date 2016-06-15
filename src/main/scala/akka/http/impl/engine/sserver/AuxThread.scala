package akka.http.impl.engine.sserver

import akka.http.impl.engine.parsing._

class AuxThread(r: Runnable) extends Thread(r) {

  private[http] var cached_bytes_rendering: RichBytesRendering = _
  private[http] var cached_bytes_rendering_with_status_200: RichBytesRendering = _

  private[http] var cached_header_parser: HttpHeaderParser = _

  override def run() = try {
    r.run()
  } finally {
    cached_bytes_rendering = null
    cached_bytes_rendering_with_status_200 = null
    cached_header_parser = null
  }

}

class AuxThreadFactory extends java.util.concurrent.ThreadFactory {

  def newThread(r: Runnable) = new AuxThread(r)
}