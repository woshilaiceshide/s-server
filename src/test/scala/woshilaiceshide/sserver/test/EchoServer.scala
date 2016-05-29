package woshilaiceshide.sserver.test

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
          channelWrapper.write("closed".map(_.toByte).toArray, true, false, false)
          channelWrapper.closeChannel(false)
        } else {
          println(s"received: ${s}")
          channelWrapper.write(bytes, true, false, false)
        }

        this

      }
      def channelIdled(channelWrapper: ChannelWrapper): Unit = {
        println("idled")
        channelWrapper.write("idle".map { _.toByte }.toArray, true, false, false)
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

  val listening_channel_configurator: ServerSocketChannelWrapper => Unit = wrapper => {
    wrapper.setOption[java.lang.Boolean](java.net.StandardSocketOptions.SO_REUSEADDR, true)
    wrapper.setBacklog(1024 * 8)
  }

  val accepted_channel_configurator: SocketChannelWrapper => Unit = wrapper => {
    wrapper.setOption[java.lang.Boolean](java.net.StandardSocketOptions.TCP_NODELAY, true)
  }

  val configurator = XNioConfigurator(count_for_reader_writers = 2, listening_channel_configurator = listening_channel_configurator, accepted_channel_configurator = accepted_channel_configurator)
  val server = NioSocketServer(
    "0.0.0.0",
    8787,
    factory,
    configurator)

  server.start(false)

}