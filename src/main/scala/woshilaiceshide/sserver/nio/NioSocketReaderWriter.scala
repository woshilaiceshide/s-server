package woshilaiceshide.sserver.nio

import java.net.InetSocketAddress
import java.util.Iterator

import java.io.Closeable
import java.io.IOException

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels._
import java.nio.charset._

import java.nio.channels.SelectionKey._

import scala.annotation.tailrec

import woshilaiceshide.sserver.utility._
import woshilaiceshide.sserver.utility.Utility

private[nio] object NioSocketReaderWriter {

  final class BytesNode(val bytes: Array[Byte], var next: BytesNode = null) {
    def append(x: Array[Byte]) = {
      if (null == x || 0 == x.length) {
        this
      } else {
        this.next = new BytesNode(x)
        this.next
      }

    }
  }
  final class BytesList(val head: BytesNode, var last: BytesNode) {
    def append(x: Array[Byte]) = {
      last = last.append(x)
    }
  }

}

class NioSocketReaderWriter private[nio] (
    channel_hander_factory: ChannelHandlerFactory,
    val configurator: NioConfigurator) extends SelectorRunner() {

  import NioSocketReaderWriter._
  import configurator._

  private val socket_max_idle_time_in_seconds_1 = if (0 < socket_max_idle_time_in_seconds) socket_max_idle_time_in_seconds else 60
  select_timeout = Math.min(socket_max_idle_time_in_seconds_1 * 1000, default_select_timeout)

  private val receive_buffer_size_1 = if (0 < receive_buffer_size) receive_buffer_size else 1 * 1024
  //this only client buffer will become read only before it's given to the handler
  private val CLIENT_BUFFER = ByteBuffer.allocate(receive_buffer_size_1)

  //some i/o operations related to those channels are pending
  //note that those operations will be checked in the order they are pended as soon as possible.
  private var pending_io_operations: ReapableQueue[MyChannelWrapper] = new ReapableQueue()
  private def pend_for_io_operation(channelWrapper: MyChannelWrapper) = {
    pending_io_operations.add(channelWrapper)
  }

  private var waiting_for_register = List[SocketChannel]()
  def register_socket_channel(target: SocketChannel): Boolean = post_to_io_thread {
    waiting_for_register = target :: waiting_for_register
  }

  protected def do_start(): Unit = {}
  protected def stop_roughly(): Unit = {

    waiting_for_register.map { safeClose(_) }
    waiting_for_register = Nil

    this.iterate_registered_keys { key =>
      val attach = key.attachment()
      attach match {
        case c: MyChannelWrapper => c.closeDirectly()
        case _ =>
      }
    }

  }
  protected def stop_gracefully(): Boolean = {

    waiting_for_register.map { safeClose(_) }
    waiting_for_register = Nil

    if (this.get_registered_size() == 0) {
      true
    } else {
      this.iterate_registered_keys { key =>
        val attach = key.attachment()
        attach match {
          case c: MyChannelWrapper => {
            c.close(false, ChannelClosedCause.SERVER_STOPPING)
            safeOp { c.justOpWriteIfNeededOrNoOp() }
          }
          case _ =>
        }
      }
      false
    }
  }
  protected def has_remaining_work(): Boolean = {
    this.get_registered_size() == 0
  }

  protected def add_a_new_socket_channel(channel: SocketChannel) = {

    channel_hander_factory.getHandler(new MyChannelInformation(channel)) match {
      case None => {
        safeClose(channel)
      }
      case Some(handler) => {
        val channelWrapper = new MyChannelWrapper(channel, handler)
        Utility.closeIfFailed(channel) {
          val key = this.register(channel, SelectionKey.OP_READ, channelWrapper)
          channelWrapper.key = key
        }
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

  private var last_check_for_idle_zombie: Long = System.currentTimeMillis()
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
    if (now - last_check_for_idle_zombie > configurator.check_idle_interval_in_seconds && !is_stopping()) {
      last_check_for_idle_zombie = now
      this.iterate_registered_keys { key =>
        key.attachment() match {
          case c: MyChannelWrapper =>
            c.checkIdle(now)
            c.checkZombie(now)
        }
      }
    }

    //check for pending i/o, and just no deadlocks
    @tailrec @inline def check_for_pending_io() {

      val reaped = pending_io_operations.reap(false)
      if (null != reaped) {
        ReapableQueueUtility.foreach(reaped, io_checker)
        check_for_pending_io()
      }

    }

    check_for_pending_io()
  }
  protected def process_selected_key(key: SelectionKey, ready_ops: Int): Unit = {

    val channel = key.channel().asInstanceOf[SocketChannel]
    val channelWrapper = key.attachment().asInstanceOf[MyChannelWrapper]

    if ((ready_ops & OP_READ) > 0) {
      try {
        val readCount = channel.read(CLIENT_BUFFER)
        if (readCount > 0) {
          CLIENT_BUFFER.flip()
          channelWrapper.bytesReceived(CLIENT_BUFFER.asReadOnlyBuffer())
        } else {
          if (!key.isValid() || !channel.isOpen()) {
            channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_UNEXPECTED)
          } else if (configurator.allow_hafl_closure) {
            //-1 can not be a hint for "closed by peer" or "just input is shutdown by peer, but output is alive".
            //I tried much, but did not catch it!
            //business codes may "ping" to find out weather the peer is fine, or just shutdown the whole socket in this situation. 
            channelWrapper.clearOpRead()
            channelWrapper.inputEnded()
          } else {
            channelWrapper.close(true, ChannelClosedCause.BECUASE_SOCKET_CLOSED_NORMALLY)
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

    if ((ready_ops & OP_WRITE) > 0) {
      try {
        channelWrapper.writing()
        if (!key.isValid() || !channel.isOpen()) {
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

  protected class MyChannelWrapper(channel: SocketChannel, private[this] var handler: ChannelHandler) extends ChannelWrapper with SelectorRunner.HasKey {

    private[nio] def isInputShutdown() = channel.socket().isInputShutdown()
    private[nio] def isOutputShutdown() = channel.socket().isOutputShutdown()

    private var last_active_time = System.currentTimeMillis()

    private var status = CHANNEL_NORMAL

    private[NioSocketReaderWriter] var key: SelectionKey = null
    def set_key(new_key: SelectionKey): Unit = this.key = new_key

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
      val should_pend = this.synchronized {
        val rightNow1 = if (rightNow) true else writes == null
        if (CHANNEL_CLOSING_RIGHT_NOW != status) {
          closed_cause = cause
          attachment_for_closed = attachment
        }
        if (CHANNEL_NORMAL == status) {
          status = if (rightNow1) CHANNEL_CLOSING_RIGHT_NOW else CHANNEL_CLOSING_GRACEFULLY
          !already_pended
        } else {
          false
        }

      }
      if (should_pend) {
        already_pended = true
        pend_for_io_operation(this)
        //if in workerThread, no need for wakeup
        if (Thread.currentThread() != NioSocketReaderWriter.this.get_worker_thread())
          NioSocketReaderWriter.this.wakeup_selector()
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
    private var already_pended = false
    private var should_generate_writing_event = false
    //if generate_writing_event is true, then 'bytesWritten' will be fired.
    def write(bytes: Array[Byte], offset: Int, length: Int, write_even_if_too_busy: Boolean, generate_writing_event: Boolean): WriteResult.Value = {

      var please_pend = false
      var please_wakeup = false

      val result = this.synchronized {
        if (CHANNEL_NORMAL == status) {

          var force_pend = false
          if (should_generate_writing_event == false && generate_writing_event == true) {
            should_generate_writing_event = generate_writing_event
            force_pend = true
          }

          this.last_active_time = System.currentTimeMillis()

          val (wr, should_pend) = if (null == bytes || 0 == bytes.length) {
            (WriteResult.WR_OK, false)

          } else if (!write_even_if_too_busy && bytes_waiting_for_written > max_bytes_waiting_for_written_per_channel) {
            (WriteResult.WR_FAILED_BECAUSE_TOO_MANY_WRITES_EXISTED, false)

          } else {

            if (null == writes) {

              @tailrec def write_immediately(buffer: ByteBuffer, times: Int): Unit = {
                if (0 < times) {
                  channel.write(buffer)
                  if (buffer.hasRemaining()) {
                    write_immediately(buffer, times - 1)
                  }
                }
              }
              //no spin here
              val buffer = ByteBuffer.wrap(bytes, offset, length)
              write_immediately(buffer, 1)
              if (buffer.hasRemaining()) {
                val tmp = new Array[Byte](buffer.remaining())
                buffer.get(tmp)
                val node = new BytesNode(tmp)
                writes = new BytesList(node, node)
              }

              //make i/o in the selector's thread
              //TODO
              /*val node = new BytesNode(bytes)
              writes = new BytesList(node, node)*/

            } else {
              writes.append(bytes)
            }

            bytes_waiting_for_written = bytes_waiting_for_written + bytes.length

            /*if (bytes_waiting_for_written > max_bytes_waiting_for_written_per_channel) {
              WriteResult.WR_OK_BUT_OVERFLOWED
            } else {
              WriteResult.WR_OK
            }*/
            (WriteResult.WR_OK, !already_pended && writes != null)

          }

          if (should_pend || (force_pend && !already_pended)) {

            already_pended = true
            please_pend = true

            //if in workerThread, no need for waking up, or processor will be wasted for one more "listen()"
            please_wakeup = Thread.currentThread() != NioSocketReaderWriter.this.get_worker_thread()

          }

          wr

        } else {
          WriteResult.WR_FAILED_BECAUSE_CHANNEL_CLOSED
        }
      }

      //pending comes before waking up
      if (please_pend) pend_for_io_operation(this)
      if (please_wakeup) NioSocketReaderWriter.this.wakeup_selector()

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
        already_pended = false

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
          //(closeIfFailed { clearOpWrite() }, status)
          (false, status)
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
    private[nio] def setOpWrite() {
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