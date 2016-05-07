package woshilaiceshide.sserver.nio

import java.net.InetSocketAddress
import java.util.Iterator

import java.io.Closeable
import java.io.IOException

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels._
import java.nio.charset._

import scala.annotation.tailrec

import woshilaiceshide.sserver.utility.Utility

class NioSocketReaderWriter(channel_hander_factory: ChannelHandlerFactory,
    receive_buffer_size: Int = 1024,
    socket_max_idle_time_in_seconds: Int = 90,
    max_bytes_waiting_for_written_per_channel: Int = 64 * 1024,
    default_select_timeout: Int = 30 * 1000,
    enable_fuzzy_scheduler: Boolean = false) extends SelectorRunner(default_select_timeout, enable_fuzzy_scheduler) {

  import Auxiliary._

  private val socket_max_idle_time_in_seconds_1 = if (0 < socket_max_idle_time_in_seconds) socket_max_idle_time_in_seconds else 60
  select_timeout = Math.min(socket_max_idle_time_in_seconds_1 * 1000, default_select_timeout)

  private val receive_buffer_size_1 = if (0 < receive_buffer_size) receive_buffer_size else 1 * 1024
  //this only client buffer will become read only before it's given to the handler
  private val CLIENT_BUFFER = ByteBuffer.allocate(receive_buffer_size_1)

  private var channels = List[MyChannelWrapper]()
  //some i/o operations related to those channels are pending
  //note that those operations will be checked in the order they are pended as soon as possible.
  private var pending_io_operations = LinkedList.newEmpty[MyChannelWrapper]()
  private def pend_for_io_operation(channelWrapper: MyChannelWrapper) = this.synchronized {
    pending_io_operations.append(channelWrapper)
  }

  private var waiting_for_register = List[SocketChannel]()
  def register_socket_channel(target: SocketChannel): Boolean = post_to_io_thread {
    waiting_for_register = target :: waiting_for_register
  }

  protected def do_start(): Unit = {}
  protected def stop_roughly(): Unit = {

    waiting_for_register.map { safeClose(_) }
    waiting_for_register = Nil

    channels.foreach { _.closeDirectly() }
    channels = Nil

  }
  protected def stop_gracefully(): Boolean = {

    waiting_for_register.map { safeClose(_) }
    waiting_for_register = Nil

    if (channels.size == 0) {
      true
    } else {
      channels.foreach { _.close(false, ChannelClosedCause.SERVER_STOPPING) }
      channels.foreach(x => safeOp { x.justOpWriteIfNeededOrNoOp() })
      false
    }
  }
  protected def has_remaining_work(): Boolean = {
    channels.size == 0
  }

  protected def add_a_new_socket_channel(channel: SocketChannel) = {

    channel_hander_factory.getHandler(new MyChannelInformation(channel)) match {
      case None => {
        safeClose(channel)
      }
      case Some(handler) => {
        val channelWrapper = new MyChannelWrapper(channel, handler)
        Utility.closeIfFailed(channel) {
          val key = channel.register(selector, SelectionKey.OP_READ, channelWrapper)
          channelWrapper.key = key
        }
        channels = channelWrapper :: channels
        channelWrapper.open()
        if (!channel.isOpen()) {
          channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
        }
      }
    }

  }

  //avoid instantiations in hot codes.
  private val io_checker = new (MyChannelWrapper => Unit) {
    def apply(channelWrapper: MyChannelWrapper): Unit = {
      channelWrapper.checkIO()
    }
  }

  private var last_check_for_idle_zombie: Long = 0
  protected def before_next_loop(): Unit = {

    if (!is_stopping() && waiting_for_register.size > 0) {
      val tmp = waiting_for_register
      waiting_for_register = Nil
      tmp.map { channel =>
        safeOp { add_a_new_socket_channel(channel) }
      }
    }

    //check for idle channels and zombie channels
    //(sometimes a channel will be closed unexpectedly and the corresponding selector will not report it.)
    val now = System.currentTimeMillis()
    if (now - last_check_for_idle_zombie > select_timeout && !is_stopping()) {
      last_check_for_idle_zombie = now
      var newChannels: List[MyChannelWrapper] = Nil
      channels.foreach { channelWrapper =>
        if (channelWrapper.checkIdle(now) != CHANNEL_CLOSED && channelWrapper.checkZombie(now) == CHANNEL_CLOSED) {
          newChannels = channelWrapper :: newChannels
        }
      }
      channels = newChannels
    }

    //check for pending i/o, and just no deadlocks
    @inline def check_for_pending_io() {
      var tmp = this.synchronized {
        if (pending_io_operations.isEmpty)
          null
        else {
          val swap = pending_io_operations
          pending_io_operations = LinkedList.newEmpty()
          swap
        }
      }
      if (null != tmp) {
        val current = System.currentTimeMillis()
        tmp.foreach { io_checker }
        check_for_pending_io()
      }

    }
    //!!!after checking the selected keys, the following statement is necessary!!!
    //!!!DO NOT loop indefinitely. twice is enough.
    check_for_pending_io()
    check_for_pending_io()
    //!!!
  }
  protected def process_selected_key(key: SelectionKey): Unit = {

    val channel = key.channel().asInstanceOf[SocketChannel]
    val channelWrapper = key.attachment().asInstanceOf[MyChannelWrapper]

    if (key.isReadable()) {
      try {
        val readCount = channel.read(CLIENT_BUFFER)
        if (readCount > 0) {
          CLIENT_BUFFER.flip()
          //!!!check before bytesReceived, or errors may occur in the overall control flow!!!
          //especially some action(sink) is abandoned flowed by 'close', then 'bytesReceived' should not be fired definitely. 
          if (!key.isValid() || !channel.isOpen()) {
            channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
          }
          channelWrapper.bytesReceived(CLIENT_BUFFER.asReadOnlyBuffer())
        } else {
          if (!key.isValid() || !channel.isOpen()) {
            channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
          } else {
            //-1 can not be a hint for "closed by peer" or "just input is shutdown by peer, but output is alive".
            //I tried much, but did not catch it!
            //business codes may "ping" to find out weather the peer is fine, or just shutdown the whole socket in this situation. 
            channelWrapper.clearOpRead()
            channelWrapper.inputEnded()
          }
        }
      } catch {
        case ex: Throwable => {
          SelectorRunner.warn(ex, "when key is readable.")
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
          SelectorRunner.warn(ex, "when key is writable.")
          channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
        }
      }
    }
  }

  protected class MyChannelWrapper(channel: SocketChannel, private[this] var handler: ChannelHandler) extends ChannelWrapper {

    import NioSocketReaderWriter._

    private var last_active_time = System.currentTimeMillis()

    private var status = CHANNEL_NORMAL

    private[NioSocketReaderWriter] var key: SelectionKey = null

    def remoteAddress: java.net.SocketAddress = channel.getRemoteAddress
    def localAddress: java.net.SocketAddress = channel.getLocalAddress

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
    def closeChannel(rightNow: Boolean = false, attachment: Option[_] = None): Unit = {
      close(rightNow, ChannelClosedCause.BY_BIZ, attachment)
    }

    private var closed_cause = ChannelClosedCause.UNKNOWN
    private var attachment_for_closed: Option[_] = None
    private[NioSocketReaderWriter] def close(rightNow: Boolean = false, cause: ChannelClosedCause.Value, attachment: Option[_] = None): Unit = {
      val should_pending = this.synchronized {
        val rightNow1 = if (rightNow) true else writes == null
        if (CHANNEL_CLOSING_RIGHT_NOW != status) {
          closed_cause = cause
          attachment_for_closed = attachment
        }
        if (CHANNEL_NORMAL == status) {
          status = if (rightNow1) CHANNEL_CLOSING_RIGHT_NOW else CHANNEL_CLOSING_GRACEFULLY
          !already_pending
        } else {
          false
        }

      }
      if (should_pending) {
        already_pending = true
        pend_for_io_operation(this)
        //if in workerThread, no need for wakeup
        if (Thread.currentThread() != NioSocketReaderWriter.this.get_worker_thread())
          NioSocketReaderWriter.this.selector.wakeup()
      }

    }

    //Only the writing to the channel is taken into account when calculating the idle-time-out by default.
    //So if transferring big files, such as in http chunking requests that last long time, use resetIdle(). 
    def resetIdle(): Unit = this.synchronized {
      this.last_active_time = System.currentTimeMillis()
    }

    def post_to_io_thread(task: Runnable): Boolean = NioSocketReaderWriter.this.post_to_io_thread(task)

    private[nio] def inputEnded() = {
      if (null != handler) {
        handler.inputEnded(this)
      }
    }

    private[nio] def bytesReceived(bytes: ByteBuffer) = {
      //DO NOT take the received event into account for idle probing!
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

    //use a (byte)flag to store the following two fields?
    private var already_pending = false
    private var should_generate_writing_event = false
    //if generate_writing_event is true, then 'bytesWritten' will be fired. 
    def write(bytes: Array[Byte], write_even_if_too_busy: Boolean, generate_writing_event: Boolean): WriteResult.Value = {

      var should_wakeup = false

      val result = this.synchronized {
        if (CHANNEL_NORMAL == status) {

          var force_pending = false
          if (should_generate_writing_event == false && generate_writing_event == true) {
            should_generate_writing_event = generate_writing_event
            force_pending = true
          }

          this.last_active_time = System.currentTimeMillis()

          val (wr, should_pending) = if (null == bytes || 0 == bytes.length) {
            (WriteResult.WR_OK, false)

          } else if (!write_even_if_too_busy && bytes_waiting_for_written > max_bytes_waiting_for_written_per_channel) {
            (WriteResult.WR_FAILED_BECAUSE_TOO_MANY_WRITES_EXISTED, false)

          } else {

            if (null == writes) {
              val node = new BytesNode(bytes)
              writes = new BytesList(node, node)
            } else {
              writes.append(bytes)
            }
            bytes_waiting_for_written = bytes_waiting_for_written + bytes.length

            /*if (bytes_waiting_for_written > max_bytes_waiting_for_written_per_channel) {
              WriteResult.WR_OK_BUT_OVERFLOWED
            } else {
              WriteResult.WR_OK
            }*/
            (WriteResult.WR_OK, !already_pending)

          }

          if (should_pending || (force_pending && !already_pending)) {
            already_pending = true
            pend_for_io_operation(this)
            //if in workerThread, no need for waking up, or processor will be wasted for one more "listen()"
            should_wakeup = Thread.currentThread() != NioSocketReaderWriter.this.get_worker_thread()

          }

          wr

        } else {
          WriteResult.WR_FAILED_BECAUSE_CHANNEL_CLOSED
        }
      }

      if (should_wakeup) NioSocketReaderWriter.this.selector.wakeup()
      result
    }

    private[nio] final def writing() {
      val tmp = this.synchronized {
        val x = writes
        writes = null
        x
      }
      if (null != tmp) {
        val remain = writing0(tmp.head, tmp.last, 0)

        //clear op_write just here for optimization.
        /*
        if (null == remain._1) {
          pend_for_io_operation(this)
        }
        */

        var become_writable = false
        this.synchronized {

          //clear op_write just here for optimization.
          if (null == remain._1 && writes == null) {
            try {
              this.clearOpWrite()
            } catch {
              case _: Throwable => { safeClose(channel); status = CHANNEL_CLOSED; }
            }
          }

          val prev_bytes_waiting_for_written = bytes_waiting_for_written
          bytes_waiting_for_written = bytes_waiting_for_written - remain._2

          if (null == writes) {
            writes = remain._1
          } else if (null != remain._1) {
            remain._1.last.next = writes.head
            writes = remain._1
          }

          if (prev_bytes_waiting_for_written > max_bytes_waiting_for_written_per_channel) {
            become_writable = bytes_waiting_for_written < max_bytes_waiting_for_written_per_channel
          }
        }
        //invoked if needed only.
        if (become_writable) {
          if (handler != null) handler.channelWritable(this)
        }

      }

    }
    @tailrec private[nio] final def writing0(head: BytesNode, last: BytesNode, written_bytes: Int): (BytesList, Int) = {

      head match {
        case null => (null, written_bytes)
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
          current - this.last_active_time > NioSocketReaderWriter.this.socket_max_idle_time_in_seconds_1 * 1000) {
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
    private[nio] def checkIO() = {

      var closedCause: ChannelClosedCause.Value = null
      var attachmentForClosed: Option[_] = None

      var generate_writing_event = false
      val (should_close, status1) = this.synchronized {

        generate_writing_event = should_generate_writing_event
        should_generate_writing_event = false

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
          (closeIfFailed {
            justOpWriteIfNeededOrNoOp()
            //TODO tell the peer not to send data??? is it harmful to the peer if the peer can not response correctly?
            channel.shutdownInput()
          }, status)
        } else if (status == CHANNEL_NORMAL && null == writes) {
          (closeIfFailed { clearOpWrite() }, status)
        } else if (status == CHANNEL_NORMAL) {
          (closeIfFailed { setOpWrite() }, status)
        } else {
          (false, status)
        }
      }
      //close outside, not in the "synchronization". keep locks clean.
      if (should_close) {
        safeOp { if (null != handler) handler.channelClosed(this, closedCause, attachmentForClosed) }
      } else {
        if (generate_writing_event) {
          if (null != this.handler) {
            val newHandler = this.handler.writtenHappened(this)
            //nothing to do with oldHandler
            this.handler = newHandler
          }
        }

      }
      status1
    }

    private[nio] def justOpWriteIfNeededOrNoOp() = this.synchronized {
      if (key != null) {
        if ((status == CHANNEL_NORMAL || status == CHANNEL_CLOSING_GRACEFULLY) && null != writes) {
          //val key = channel.keyFor(selector)
          key.interestOps(SelectionKey.OP_WRITE)
        } else {
          //val key = channel.keyFor(selector)
          key.interestOps(0)
        }
      }

    }
    private def setOpWrite() {
      if (key != null) {
        //val key = channel.keyFor(selector)
        var alreadyOps = key.interestOps()
        if ((alreadyOps & SelectionKey.OP_WRITE) == 0) {
          alreadyOps |= SelectionKey.OP_WRITE
          key.interestOps(alreadyOps)
        }
      }
    }

    private def clearOpWrite() {
      if (key != null) {
        //val key = channel.keyFor(selector)
        var alreadyOps = key.interestOps()
        if ((alreadyOps & SelectionKey.OP_WRITE) != 0) {
          alreadyOps &= ~SelectionKey.OP_WRITE
          key.interestOps(alreadyOps)
        }
      }
    }

    private[nio] def clearOpRead() {
      if (key != null) {
        //val key = channel.keyFor(selector)
        var alreadyOps = key.interestOps()
        if ((alreadyOps & SelectionKey.OP_READ) != 0) {
          alreadyOps &= ~SelectionKey.OP_READ
          key.interestOps(alreadyOps)
        }
      }
    }
  }

}