package woshilaiceshide.sserver.nio

import java.net.InetSocketAddress
import java.util.Iterator

import java.io.Closeable
import java.io.IOException

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.channels.ClosedSelectorException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CharsetEncoder

import scala.annotation.tailrec

trait ChannelHandler {

  def toBytesArray(byteBuffer: ByteBuffer) = {
    val count = byteBuffer.remaining()
    val bytes = new Array[Byte](count)
    byteBuffer.get(bytes)
    bytes
  }

  def channelOpened(channelWrapper: NioSocketServer#ChannelWrapper): Unit
  def inputEnded(channelWrapper: NioSocketServer#ChannelWrapper): Unit
  def bytesReceived(byteBuffer: ByteBuffer, channelWrapper: NioSocketServer#ChannelWrapper): ChannelHandler
  //if the handler does not close the channel, then the channel will closed roughly. 
  def channelIdled(channelWrapper: NioSocketServer#ChannelWrapper): Unit
  //too many bytes waiting for transport to stop sending previously, 
  //now please continue your transport.
  def becomeWritable(channelWrapper: NioSocketServer#ChannelWrapper): Unit
  def channelClosed(channelWrapper: NioSocketServer#ChannelWrapper, cause: NioSocketServer.ChannelClosedCause.Value, attachment: Option[_]): Unit
}
trait ChannelHandlerFactory {
  def getChannelHandler(aChannel: AChannel): Option[ChannelHandler]
}

final class AChannel(channel: SocketChannel) {
  def remoteAddress = channel.getRemoteAddress
  def localAddress = channel.getLocalAddress
}

private[nio] final class BytesNode(val bytes: Array[Byte], var next: BytesNode = null) {
  def append(x: Array[Byte]) = {
    if (null == x || 0 == x.length) {
      this
    } else {
      this.next = new BytesNode(x)
      this.next
    }

  }
}
private[nio] final class BytesList(val head: BytesNode, var last: BytesNode) {
  def append(x: Array[Byte]) = {
    last = last.append(x)
  }
}

object NioSocketServer {

  def warn(ex: Throwable, msg: String = "empty message") = {
    Console.err.print(msg)
    Console.err.print(" ")
    ex.printStackTrace(Console.err)
  }

  val INITIALIZED = 0
  val STARTED = 1
  val STOPPING = 2
  val STOPPED_GRACEFULLY = 4
  val STOPPED_ROUGHLY = 8

  val CHANNEL_NORMAL = 0
  val CHANNEL_CLOSING_GRACEFULLY = 1
  val CHANNEL_CLOSING_RIGHT_NOW = 2
  val CHANNEL_CLOSED = 3

  object ChannelClosedCause extends scala.Enumeration {
    val UNKNOWN = Value
    val BY_BIZ = Value
    val SERVER_STOPPING = Value
    val BY_PEER = Value
    val BECUASE_INPUTSTREAM_ENDED = Value
    val BECUASE_SOCKET_CLOSED_UNEXPECTED = Value
    val BECAUSE_IDLE = Value
  }

  object WriteResult extends scala.Enumeration {
    val WR_OK = Value
    val WR_FAILED_BECAUSE_TOO_MANY_WRITES_EXISTED = Value
    val WR_FAILED_BECAUSE_CHANNEL_CLOSED = Value
    val WR_FAILED_BECAUSE_EMPTY_BYTES_TO_WRITTEN = Value
    val WR_FAILED_BECAUSE_BYTES_TOO_BIG = Value
    val WR_FAILED_BECAUSE_SERVER_STOPING_STOPPED = Value
  }

  private[nio] case class TimedTask(timestamp: Long, runnable: Runnable)
}

class NioSocketServer(interface: String,
                      port: Int,
                      channel_hander_factory: ChannelHandlerFactory,
                      receive_buffer_size: Int = 1024,
                      socket_max_idle_time_in_seconds_0: Int = 60 * 5,
                      max_bytes_waiting_for_written_per_channel: Int = 64 * 1024,
                      enable_fuzzy_scheduler: Boolean = false) {

  import NioSocketServer._

  private val receive_buffer_size_1 = if (0 < receive_buffer_size) receive_buffer_size else 1 * 1024
  private val socket_max_idle_time_in_seconds = if (0 < socket_max_idle_time_in_seconds_0) socket_max_idle_time_in_seconds_0 else 60 * 1000
  private val default_select_timeout = 30 * 1000
  private var select_timeout = Math.min(socket_max_idle_time_in_seconds, default_select_timeout)

  private val CLIENT_BUFFER = ByteBuffer.allocate(receive_buffer_size_1)
  private val ssc = ServerSocketChannel.open()
  private val selector = Selector.open()

  private def safeClose(x: Closeable) = try { x.close(); } catch { case _: Throwable => {} }
  private def safeOp[T](x: => T) = try { x } catch { case _: Throwable => {} }

  private var workerThread: Thread = null

  private var channels = List[ChannelWrapper]()
  private var pending_io_operations = List[ChannelWrapper]()
  private def pending_for_io_operation(channelWrapper: ChannelWrapper) = this.synchronized {
    pending_io_operations = channelWrapper :: pending_io_operations
  }

  private var status = 0

  private var tasks: List[Runnable] = Nil
  def post(task: Runnable) = this.synchronized {
    if (STARTED != status) {
      false
    } else {
      tasks = task :: tasks
      true
    }
  }

  private var timed_tasks: List[TimedTask] = Nil
  def scheduleFuzzily(task: Runnable, delayInSeconds: Int) = this.synchronized {
    if (STARTED != status) {
      false
    } else {
      timed_tasks = TimedTask(System.currentTimeMillis() + delayInSeconds * 1000, task) :: timed_tasks
      true
    }
  }

  def start(asynchronously: Boolean = true) = {
    var listenInCurrentThread = false
    this.synchronized {
      if (INITIALIZED == status) {
        ssc.socket().bind(new InetSocketAddress(interface, port))
        ssc.configureBlocking(false)
        ssc.register(selector, SelectionKey.OP_ACCEPT)
        status = STARTED
      }

      if (asynchronously) {
        workerThread = new Thread(s"selected-httpd-${System.currentTimeMillis()}") {
          override def run() = safe_listen()
        }
        workerThread.start()
      } else {
        workerThread = Thread.currentThread()
        listenInCurrentThread = true
      }

    }
    if (listenInCurrentThread) safe_listen()
  }

  private def safe_listen() {
    try { listen() }
    catch {
      case _: Throwable => {
        safeClose(selector)
        safeClose(ssc)
        channels.foreach { _.closeDirectly() }
        channels = Nil
        this.synchronized {
          status = STOPPED_GRACEFULLY
          this.notifyAll()
        }

      }
    }
  }

  private var stop_deadline: Long = 0
  def stop(timeout: Int) = {
    if (workerThread == Thread.currentThread()) this.synchronized {
      if (STARTED == status) {
        status = STOPPING
        select_timeout = Math.min(Math.max(0, timeout), select_timeout)
        stop_deadline = Math.max(0, timeout) + System.currentTimeMillis()
      }
    }
    else this.synchronized {
      if (STARTED == status) {
        status = STOPPING
        stop_deadline = Math.max(0, timeout) + System.currentTimeMillis()
        selector.wakeup()
        this.wait(if (0 > timeout) 0 else timeout)
        if (status == STOPPING) {
          safeClose(selector)
          safeClose(ssc)
          status == STOPPED_ROUGHLY
        }
      }
      status
    }
  }
  def join(timeout: Long) = {
    if (workerThread == Thread.currentThread()) this.synchronized {
      workerThread.join(timeout)
    }
  }

  def getStatus() = this.synchronized { status }

  private var already_in_stopping = false
  private var last_check_for_idle_zombie: Long = 0
  @tailrec private def listen() {

    try {
      val selected = selector.select(select_timeout)

      if (selected > 0) {
        val iterator = selector.selectedKeys().iterator()
        while (iterator.hasNext()) {
          val key = iterator.next()
          iterator.remove()
          process(key)
        }
      }
    } catch {
      case _: ClosedSelectorException => {}
      case _: Throwable => {
        safeClose(selector)
        safeClose(ssc)
      }
    }

    val continue = if ((!selector.isOpen()) || this.synchronized { status == STOPPING && stop_deadline < System.currentTimeMillis() }) {
      //closed
      safeClose(ssc)
      channels.foreach { _.closeDirectly() }
      channels = Nil
      this.synchronized {
        status = STOPPED_GRACEFULLY
        this.notifyAll()
      }
      false
    } else if (!already_in_stopping && this.synchronized { status == STOPPING }) {
      //stopping
      already_in_stopping = true
      if (channels.size == 0) {
        safeClose(selector)
        safeClose(ssc)
        this.synchronized {
          status = STOPPED_GRACEFULLY
          this.notifyAll()
        }
        false
      } else {
        channels.foreach { _.close(false, ChannelClosedCause.SERVER_STOPPING) }
        safeOp { ssc.keyFor(selector).interestOps(0) }
        channels.foreach(x => safeOp { x.justOpWriteIfNeededOrNoOp() })
        true
      }
    } else if (already_in_stopping && channels.size == 0) {
      safeClose(selector)
      safeClose(ssc)
      this.synchronized {
        status = STOPPED_GRACEFULLY
        this.notifyAll()
      }
      false
    } else {
      true
    }

    var tasks_to_do = this.synchronized { if (0 == tasks.size) Nil else { val tmp = tasks; tasks = Nil; tmp } }
    tasks_to_do.foreach { x => safeOp(x.run()) }

    if (enable_fuzzy_scheduler) {
      var timed_tasks_to_do = this.synchronized {
        if (0 == timed_tasks.size) Nil
        else {
          val now = System.currentTimeMillis()
          val tmp = timed_tasks.groupBy { x => x.timestamp >= now }
          val timeout = tmp(true);
          timed_tasks = tmp(false);
          timeout
        }
      }
      timed_tasks_to_do.foreach { x => safeOp { x.runnable.run() } }
    }

    if (continue) {
      //check for idle channels and zombie channels
      //(sometimes a channel will be closed unexpectedly and the corresponding selector will not report it.)
      val now = System.currentTimeMillis()
      if (now - last_check_for_idle_zombie > select_timeout && !already_in_stopping) {
        last_check_for_idle_zombie = now
        var newChannels: List[ChannelWrapper] = Nil
        channels.foreach { channelWrapper =>
          if (channelWrapper.checkIdle(now) != CHANNEL_CLOSED && channelWrapper.checkZombie(now) == CHANNEL_CLOSED) {
            newChannels = channelWrapper :: newChannels
          }
        }
        channels = newChannels
      }

      //check for pending i/o, and just no deadlocks
      @tailrec def check_for_pending_io() {
        var tmp: List[ChannelWrapper] = Nil
        this.synchronized {
          tmp = pending_io_operations
          pending_io_operations = Nil
        }
        if (0 < tmp.size) {
          val current = System.currentTimeMillis()
          tmp.foreach { _.check(current) }
          check_for_pending_io()
        }

      }
      check_for_pending_io()
      listen()
    }
  }

  def process(key: SelectionKey) {
    if (key.isAcceptable()) {
      val ssc = key.channel().asInstanceOf[ServerSocketChannel]
      val channel = ssc.accept()
      var channelWrapper: ChannelWrapper = null
      try {
        channel.configureBlocking(false)
        channel_hander_factory.getChannelHandler(new AChannel(channel)) match {
          case None => {
            safeClose(channel)
          }
          case Some(handler) => {
            channelWrapper = new ChannelWrapper(channel, handler)
            channel.register(selector, SelectionKey.OP_READ, channelWrapper)
            channelWrapper.open()
            if (!key.isValid()) {
              channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
            } else {
              channels = channelWrapper :: channels
            }
          }
        }
      } catch {
        case ex: Throwable => {
          warn(ex, "when key is acceptable.")
          if (null == channelWrapper) {
            safeClose(channel)
          } else {
            channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
          }
        }
      }

    } else {

      val channel = key.channel().asInstanceOf[SocketChannel]
      val channelWrapper = key.attachment().asInstanceOf[ChannelWrapper]

      if (key.isReadable()) {
        try {
          val readCount = channel.read(CLIENT_BUFFER)
          if (readCount > 0) {
            CLIENT_BUFFER.flip()
            channelWrapper.bytesReceived(CLIENT_BUFFER.asReadOnlyBuffer())
            if (!key.isValid()) {
              channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
            }
          } else {
            if (!key.isValid() || !channel.isOpen()) {
              channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
            } else {
              //-1 can not be a hint for "closed by peer" or "just input is shutdown by peer, but output is alive".
              //I tried much, but did not catch it!
              //Business codes may "ping" to find out weather the peer is fine, or just shutdown the whole socket in this situation. 
              channelWrapper.clearOpRead()
              channelWrapper.inputEnded()
            }
          }
        } catch {
          case ex: Throwable => {
            warn(ex, "when key is readable.")
            channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
          }
        } finally {
          CLIENT_BUFFER.clear()
        }
      }

      if (key.isWritable()) {
        try {
          channelWrapper.writing()
          if (!key.isValid()) {
            channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
          }
        } catch {
          case ex: Throwable => {
            warn(ex, "when key is writable.")
            channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
          }
        }
      }
    }
  }

  class ChannelWrapper(channel: SocketChannel, private[this] var handler: ChannelHandler) {

    import NioSocketServer._

    private var last_active_time = System.currentTimeMillis()

    private var status = CHANNEL_NORMAL

    def remoteAddress = channel.getRemoteAddress
    def localAddress = channel.getLocalAddress

    private[nio] def closeDirectly() {
      val should = this.synchronized {
        val tmp = status
        status = CHANNEL_CLOSED
        tmp != CHANNEL_CLOSED
      }
      if (should) {
        safeClose(this.channel)
        if (null != handler) safeOp {
          handler.channelClosed(this, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED, None)
          handler = null
        }
      }

    }

    private[nio] def open() {
      //handler should be not null at this time.
      /*if (null != handler)*/ handler.channelOpened(this)
    }

    //I'm intended for usage by business codes if needed.
    def closeChannel(rightNow: Boolean = false, attachment: Option[_] = None) = {
      close(rightNow, ChannelClosedCause.BY_BIZ, attachment)
    }

    private var closed_cause = ChannelClosedCause.UNKNOWN
    private var attachment_for_closed: Option[_] = None
    private[NioSocketServer] def close(rightNow: Boolean = false, cause: ChannelClosedCause.Value, attachment: Option[_] = None) = {
      val should_pending = this.synchronized {
        val rightNow1 = if (rightNow) true else writes == null
        if (CHANNEL_CLOSING_RIGHT_NOW != status) {
          closed_cause = cause
          attachment_for_closed = attachment
        }
        if (CHANNEL_NORMAL == status) {
          status = if (rightNow1) CHANNEL_CLOSING_RIGHT_NOW else CHANNEL_CLOSING_GRACEFULLY
          if (already_pending) {
            false
          } else {
            already_pending = true
            true
          }
        } else {
          false
        }

      }
      if (should_pending) {
        pending_for_io_operation(this)
        //if in workerThread, no need for wakeup
        if (Thread.currentThread() != NioSocketServer.this.workerThread)
          NioSocketServer.this.selector.wakeup()
      }

    }

    //Only the writing to the channel is taken into account when calculating the idle-time-out by default.
    //So if transferring big files, such as in http chunking requests that last long time, use resetIdle(). 
    def resetIdle() = this.synchronized {
      this.last_active_time = System.currentTimeMillis()
    }

    def post(task: Runnable): Boolean = NioSocketServer.this.post(task)
    def is_in_io_worker_thread() = Thread.currentThread() == NioSocketServer.this.workerThread

    private[nio] def inputEnded() = {
      if (null != handler) {
        handler.inputEnded(this)
      }
    }

    private[nio] def bytesReceived(bytes: ByteBuffer) = {
      //DO NOT take the received event into account!
      //If ... then use resetIdel()
      /*this.synchronized {
        this.last_active_time = System.currentTimeMillis()
      }*/
      //perfectly
      if (null != handler) {
        //just for test
        /*while (bytes.hasRemaining()) {
          val b = ByteBuffer.wrap(Array(bytes.get()))
          val newHandler = handler.bytesReceived(b, this)
          //nothing to do with oldHandler
          this.handler = newHandler
        }*/
        val newHandler = handler.bytesReceived(bytes, this)
        //nothing to do with oldHandler
        this.handler = newHandler
      } else {
        //ignored or shutdown the tcp's upstream channel?
      }

    }

    private var writes: BytesList = null
    private var bytes_waiting_for_written = 0
    //if bytes waiting for written is more than max_bytes_waiting_for_written_per_channel and write_if_too_busy is true, 
    //then no bytes will be written.
    def write(bytes: Array[Byte], write_if_too_busy: Boolean = false): WriteResult.Value = {
      if (null != bytes && 0 < bytes.length) {
        write0(bytes)
      } else {
        WriteResult.WR_FAILED_BECAUSE_EMPTY_BYTES_TO_WRITTEN
      }
    }
    private var already_pending = false
    private def write0(bytes: Array[Byte], write_if_too_busy: Boolean = false): WriteResult.Value = {
      this.synchronized {
        if (CHANNEL_NORMAL == status) {

          this.last_active_time = System.currentTimeMillis()

          if (bytes.length >= max_bytes_waiting_for_written_per_channel) {
            WriteResult.WR_FAILED_BECAUSE_BYTES_TOO_BIG
          } else if (!write_if_too_busy && bytes_waiting_for_written > max_bytes_waiting_for_written_per_channel) {
            WriteResult.WR_FAILED_BECAUSE_TOO_MANY_WRITES_EXISTED
          } else {
            val should_pending = {
              if (null == writes) {
                val node = new BytesNode(bytes)
                writes = new BytesList(node, node)
              } else {
                writes.append(bytes)
              }
              bytes_waiting_for_written = bytes_waiting_for_written + bytes.length
              if (already_pending) {
                false
              } else {
                already_pending = true
                true
              }
            }
            if (should_pending) {
              pending_for_io_operation(this)
              //if in workerThread, no need for wakeup, or processor will be wasted for one more "listen()" 
              if (Thread.currentThread() != NioSocketServer.this.workerThread)
                NioSocketServer.this.selector.wakeup()
            }
            WriteResult.WR_OK

          }
        } else {
          WriteResult.WR_FAILED_BECAUSE_CHANNEL_CLOSED
        }
      }

    }

    private[nio] final def writing() {
      val tmp = this.synchronized {
        val x = writes
        writes = null
        x
      }
      if (null != tmp) {
        val remain = writing0(tmp.head, tmp.last, 0)
        if (null == remain) {
          pending_for_io_operation(this)
        } else {
          var become_writable = false
          this.synchronized {
            if (null == writes) {
              writes = remain._1
            } else {
              remain._1.last.next = writes.head
              writes = remain._1
            }

            if (bytes_waiting_for_written > max_bytes_waiting_for_written_per_channel) {
              bytes_waiting_for_written = bytes_waiting_for_written - remain._2
              become_writable = bytes_waiting_for_written < max_bytes_waiting_for_written_per_channel
            } else {
              bytes_waiting_for_written = bytes_waiting_for_written - remain._2
            }
          }
          //invoked if needed only.
          if (become_writable) {
            if (handler != null) handler.becomeWritable(this)
          }
        }
      }

    }
    @tailrec private[nio] final def writing0(head: BytesNode, last: BytesNode, written_bytes: Int): (BytesList, Int) = {

      head match {
        case null => null
        case node => {
          val written = channel.write(ByteBuffer.wrap(node.bytes))
          if (written == node.bytes.length) {
            writing0(head.next, last, written_bytes + written)
          } else if (written > 0) {
            val remain = new Array[Byte](node.bytes.length - written)
            System.arraycopy(node.bytes, written, remain, 0, remain.length)
            val newNode = new BytesNode(remain, head.next)
            (new BytesList(newNode, if (head eq last) newNode else last), written_bytes + written)
          } else if (0 == written) {
            (new BytesList(head, last), written_bytes)
          } else {
            (new BytesList(head, last), written_bytes)
          }
        }
      }

    }

    private[nio] def checkIdle(current: Long) = {
      val (should, status1) = this.synchronized {
        if (status == CHANNEL_NORMAL &&
          current - this.last_active_time > NioSocketServer.this.socket_max_idle_time_in_seconds * 1000) {
          (true, status)
        } else {
          (false, status)
        }
      }
      if (should) {
        if (null != handler) {
          handler.channelIdled(this)
        }
        this.close(true, ChannelClosedCause.BECAUSE_IDLE)
      }
      status
    }
    private[nio] def checkZombie(current: Long) = this.synchronized {
      if (status == CHANNEL_NORMAL && !this.channel.isOpen()) {
        this.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
      }
      status
    }
    private def closeIfFailed(x: => Unit) = {
      try {
        x; false;
      } catch {
        case _: Throwable => { safeClose(channel); status = CHANNEL_CLOSED; true; }
      }
    }
    private[nio] def check(current: Long) = {
      var closedCause: ChannelClosedCause.Value = null
      var attachmentForClosed: Option[_] = None
      val (should, status1) = this.synchronized {

        closedCause = closed_cause
        attachmentForClosed = attachment_for_closed
        already_pending = false

        if (status == CHANNEL_CLOSING_RIGHT_NOW) {
          safeClose(channel)
          writes = null
          status = CHANNEL_CLOSED
          (true, status)
        } else if (status == CHANNEL_CLOSED) {
          (false, status)
        } else if (status == CHANNEL_CLOSING_GRACEFULLY && null == writes) {
          safeClose(channel)
          status = CHANNEL_CLOSED
          (true, status)
        } else if (status == CHANNEL_CLOSING_GRACEFULLY) {
          //(closeIfFailed { setOpWrite() }, status)
          (closeIfFailed { justOpWriteIfNeededOrNoOp() }, status)
        } else if (status == CHANNEL_NORMAL && null == writes) {
          (closeIfFailed { clearOpWrite() }, status)
        } else if (status == CHANNEL_NORMAL) {
          (closeIfFailed { setOpWrite() }, status)
        } else {
          (false, status)
        }
      }
      if (should) safeOp { if (null != handler) handler.channelClosed(this, closedCause, attachmentForClosed) }
      status1
    }

    private[nio] def justOpWriteIfNeededOrNoOp() = this.synchronized {
      if ((status == CHANNEL_NORMAL || status == CHANNEL_CLOSING_GRACEFULLY) && null != writes) {
        val key = channel.keyFor(selector)
        key.interestOps(SelectionKey.OP_WRITE)
      } else {
        val key = channel.keyFor(selector)
        key.interestOps(0)
      }
    }
    private def setOpWrite() {
      val key = channel.keyFor(selector)
      var alreadyOps = key.interestOps()
      if ((alreadyOps & SelectionKey.OP_WRITE) == 0) {
        alreadyOps |= SelectionKey.OP_WRITE
        key.interestOps(alreadyOps)
      }
    }
    private def clearOpWrite() {
      val key = channel.keyFor(selector)
      var alreadyOps = key.interestOps()
      if ((alreadyOps & SelectionKey.OP_WRITE) != 0) {
        alreadyOps &= ~SelectionKey.OP_WRITE
        key.interestOps(alreadyOps)
      }
    }

    private[nio] def clearOpRead() {
      val key = channel.keyFor(selector)
      var alreadyOps = key.interestOps()
      if ((alreadyOps & SelectionKey.OP_READ) != 0) {
        alreadyOps &= ~SelectionKey.OP_READ
        key.interestOps(alreadyOps)
      }
    }
  }

}