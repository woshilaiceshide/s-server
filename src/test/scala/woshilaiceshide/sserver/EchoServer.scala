package woshilaiceshide.sserver

import woshilaiceshide.sserver.nio._

object EchoServer extends App {

  val factory = new ChannelHandlerFactory() {

    val handler = Some(new ChannelHandler() {

      def channelOpened(channelWrapper: NioSocketServer#ChannelWrapper): Unit = {
        println("open...")
      }
      def inputEnded(channelWrapper: NioSocketServer#ChannelWrapper) = channelWrapper.closeChannel(false)
      def bytesReceived(byteBuffer: java.nio.ByteBuffer, channelWrapper: NioSocketServer#ChannelWrapper): ChannelHandler = {

        val bytes = this.toBytesArray(byteBuffer)
        val quit = "quit".map(_.toByte).toArray
        val s = new String(bytes.map(_.toChar))
        if (bytes.length >= quit.length && s.startsWith("quit")) {
          println("will quit")
          channelWrapper.write("closed".map(_.toByte).toArray)
          channelWrapper.closeChannel(false)
        } else {
          println(s"received: ${s}")
          channelWrapper.write(bytes)
        }

        this

      }
      def channelIdled(channelWrapper: NioSocketServer#ChannelWrapper): Unit = {
        println("idled")
        channelWrapper.write("idle".map { _.toByte }.toArray)
        channelWrapper.closeChannel(false)
      }
      def becomeWritable(channelWrapper: NioSocketServer#ChannelWrapper): Unit = {

      }
      def channelClosed(channelWrapper: NioSocketServer#ChannelWrapper, cause: NioSocketServer.ChannelClosedCause.Value, attachment: Option[_]): Unit = {
        println("closed...")
      }

    })
    def getChannelHandler(aChannel: AChannel): Option[ChannelHandler] = handler
  }

  val server = new NioSocketServer("127.0.0.1", 8181, factory)
  server.start(false)

}