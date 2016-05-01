package woshilaiceshide.sserver

import woshilaiceshide.sserver.nio._

object EchoServer extends App {

  val factory = new ChannelHandlerFactory() {

    val handler = Some(new ChannelHandler() {

      def channelOpened(channelWrapper: ChannelWrapper): Unit = {
        println("open...")
      }
      def inputEnded(channelWrapper: ChannelWrapper) = channelWrapper.closeChannel(false)
      def bytesReceived(byteBuffer: java.nio.ByteBuffer, channelWrapper: ChannelWrapper): ChannelHandler = {

        val bytes = woshilaiceshide.sserver.utility.Utility.toBytesArray(byteBuffer)
        val quit = "quit".map(_.toByte).toArray
        val s = new String(bytes.map(_.toChar))
        if (bytes.length >= quit.length && s.startsWith("quit")) {
          println("will quit")
          channelWrapper.write("closed".map(_.toByte).toArray, true, false)
          channelWrapper.closeChannel(false)
        } else {
          println(s"received: ${s}")
          channelWrapper.write(bytes, true, false)
        }

        this

      }
      def channelIdled(channelWrapper: ChannelWrapper): Unit = {
        println("idled")
        channelWrapper.write("idle".map { _.toByte }.toArray, true, false)
        channelWrapper.closeChannel(false)
      }
      def channelWritable(channelWrapper: ChannelWrapper): Unit = {

      }
      def channelClosed(channelWrapper: ChannelWrapper, cause: ChannelClosedCause.Value, attachment: Option[_]): Unit = {
        println("closed...")
      }

      def writtenHappened(channelWrapper: ChannelWrapper): ChannelHandler = this

    })

    def getHandler(channel: ChannelInformation): Option[ChannelHandler] = handler
  }

  val reuse_addr = NioSocketServer.SOption[java.lang.Boolean](java.net.StandardSocketOptions.SO_REUSEADDR, true)
  val server = new NioSocketServer("127.0.0.1", 8181, factory, listening_socket_options = List(reuse_addr))
  server.start(false)

}