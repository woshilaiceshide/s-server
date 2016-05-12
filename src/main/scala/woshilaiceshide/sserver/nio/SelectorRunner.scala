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

object SelectorRunner {

  private[nio] def warn(ex: Throwable, msg: String = "empty message") = {
    Console.err.print(msg)
    Console.err.print(" ")
    ex.printStackTrace(Console.err)
  }

  val INITIALIZED = 0
  val STARTED = 1
  val STOPPING = 2
  val STOPPED_GRACEFULLY = 4
  val STOPPED_ROUGHLY = 8
  val BAD = 16

}

/**
 * read/write bytes over the accepted(connected) socket channels.
 *
 */
abstract class SelectorRunner(default_select_timeout: Int = 30 * 1000,
    enable_fuzzy_scheduler: Boolean = false) {

  import Auxiliary._
  import SelectorRunner._

  //private val default_select_timeout = 30 * 1000
  protected var select_timeout = default_select_timeout

  import java.util.concurrent.locks.ReentrantReadWriteLock
  private val lock_for_selector = new ReentrantReadWriteLock(false)
  private var selector = Selector.open()
  //epoll's 100% cpu bug
  //this method will be invoked in the i/o thread only.
  private def rebuild_selector() = {
    val lock = lock_for_selector.writeLock()
    lock.lock()
    try {
      //TODO to be done
    } finally {
      lock.unlock()
    }
  }
  //no lock is needed.
  private def close_selector() = { if (null != selector) safeClose(selector) }
  /**
   * this method is thread safe.
   */
  def wakeup_selector() = {
    val lock = lock_for_selector.readLock()
    lock.lock()
    try {
      if (null != selector) selector.wakeup()
    } finally {
      lock.unlock()
    }
  }
  def register(ch: SelectableChannel, ops: Int, att: Object) = {
    val lock = lock_for_selector.readLock()
    lock.lock()
    try {
      if (null != selector) ch.register(selector, ops, att)
      else null
    } finally {
      lock.unlock()
    }
  }

  def safeClose(x: Closeable) = try { x.close(); } catch { case ex: Throwable => { ex.printStackTrace() } }
  def safeOp[T](x: => T) = try { x } catch { case ex: Throwable => { ex.printStackTrace() } }

  private var worker_thread: Thread = null
  def get_worker_thread() = worker_thread

  private var status = INITIALIZED

  private val lock_for_tasks = new Object
  private var tasks: LinkedList[Runnable] = LinkedList.newEmpty()
  /**
   * only can be posted when the runner is in 'STARTED' status.
   */
  def post_to_io_thread(task: => Unit): Boolean = post_to_io_thread(new Runnable() {
    def run() = task
  })
  /**
   * only can be posted when the runner is in 'STARTED' status.
   */
  def post_to_io_thread(task: Runnable): Boolean = this.synchronized {
    if (STARTED != status) {
      false
    } else {
      lock_for_tasks.synchronized {
        tasks.append(task)
      }
      selector.wakeup()
      true
    }
  }
  private def reap_tasks() = {
    val tasks_to_do = lock_for_tasks.synchronized {
      if (tasks.isEmpty) {
        //LinkedList.newEmpty()
        null
      } else {
        val tmp = tasks;
        tasks = LinkedList.newEmpty();
        tmp
      }
    }
    if (null != tasks_to_do)
      tasks_to_do.foreach { safe_runner }
  }

  //please invoke this method after you start the nio socket server.
  def is_in_io_worker_thread(): Boolean = Thread.currentThread() == this.worker_thread

  private val lock_for_timed_tasks = new Object
  private var timed_tasks: LinkedList[TimedTask] = LinkedList.newEmpty()
  def scheduleFuzzily(task: Runnable, delayInSeconds: Int) = {
    if (!enable_fuzzy_scheduler) {
      false
    } else this.synchronized {
      if (STARTED != status) {
        false
      } else {
        val timed_task = TimedTask(System.currentTimeMillis() + delayInSeconds * 1000, task)
        lock_for_timed_tasks.synchronized {
          timed_tasks.append(timed_task)
        }
        true
      }
    }
  }
  private def reap_timed_tasks() = {
    if (enable_fuzzy_scheduler) {
      val timed_tasks_to_do = lock_for_timed_tasks.synchronized {
        if (timed_tasks.isEmpty) {
          null
        } else {
          val now = System.currentTimeMillis()
          val tmp = timed_tasks.group_by_fitler { x => x.when_to_run <= now }
          timed_tasks = tmp.unfiltered;
          tmp.filtered
        }
      }
      if (null != timed_tasks_to_do)
        timed_tasks_to_do.foreach { x => safeOp { x.runnable.run() } }
    }
  }

  private var terminated = false
  private val lock_for_terminated = new Object
  private var when_terminated: LinkedList[Runnable] = LinkedList.newEmpty()
  def registerOnTermination[T](code: => T) = lock_for_terminated.synchronized {
    if (terminated) {
      false
    } else {
      when_terminated.append(new Runnable { def run = code })
      true
    }
  }
  private def reap_terminated() = {
    val termination_sinks = lock_for_terminated.synchronized {
      //disable registerOnTermination first, before executing the sinks.
      terminated = true
      val tmp = when_terminated
      when_terminated = null
      this.notifyAll()
      tmp
    }
    termination_sinks.foreach { x => safeOp(x.run()) }
  }

  /**
   * some work to do right before this runner starts
   */
  protected def do_start(): Unit
  /**
   * this runner is bad, and it should be shutdown forcibly.
   */
  protected def stop_roughly(): Unit
  /**
   * if true returned(it means "no more work left"), I'll stop immediately.
   */
  protected def stop_gracefully(): Boolean
  /**
   * if true, then some work is in progress so this runner can not be stopped.
   */
  protected def has_remaining_work(): Boolean
  /**
   * just before the next loop.
   */
  protected def before_next_loop(): Unit
  /**
   * process the selected key
   */
  protected def process_selected_key(key: SelectionKey): Unit

  def start(asynchronously: Boolean = true) = {
    var continued = this.synchronized {
      if (INITIALIZED == status) {
        status = STARTED
        true
      } else {
        false
      }
    }
    if (continued) {
      try {
        do_start()
      } catch {
        case ex: Throwable => {
          continued = false
          this.synchronized {
            status = BAD
          }
          close_selector()
          warn(ex)
        }
      }
    }
    if (continued) {
      if (asynchronously) {
        //worker_thread should not be assigned in the new thread's running.
        worker_thread = new Thread(s"sserver-selector-h${hashCode()}-t${System.currentTimeMillis()}") {
          override def run() = safe_loop()
        }
        worker_thread.start()
      } else {
        worker_thread = Thread.currentThread()
        safe_loop()
      }
    }
  }

  private def safe_loop() {
    try {
      loop()
    } catch {
      case ex: Throwable => {
        warn(ex)
        this.synchronized {
          status = STOPPED_ROUGHLY
        }
        stop_roughly()
      }
    } finally {
      close_selector()
      reap_tasks()
      reap_timed_tasks()
      reap_terminated()
    }
  }

  private var stop_deadline: Long = 0
  /**
   * if i'm stopped using a "timeout", then this is the deadline.
   */
  def get_stop_deadline() = this.synchronized { stop_deadline }
  /**
   * the status is returned.
   *
   * Note that this method returned immediately. use "def join(...)" to wait its termination.
   */
  def stop(timeout: Int) = {
    if (worker_thread == Thread.currentThread()) {
      this.synchronized {
        if (STARTED == status) {
          status = STOPPING
          select_timeout = Math.min(Math.max(0, timeout), select_timeout)
          //stop_deadline is necessary.
          stop_deadline = Math.max(0, timeout) + System.currentTimeMillis()
        }
        status
      }
    } else this.synchronized {
      if (STARTED == status) {
        status = STOPPING
        stop_deadline = Math.max(0, timeout) + System.currentTimeMillis()
        safeOp { selector.wakeup() }
        safeOp { worker_thread.interrupt() }
      }
      status
    }
  }
  def join(timeout: Long) = {
    if (worker_thread != Thread.currentThread()) {
      worker_thread.join(timeout)
    }
  }

  def getStatus() = this.synchronized { status }

  //avoid instantiations in hot codes.
  private val safe_runner = new (Runnable => Unit) {
    def apply(r: Runnable): Unit = {
      try {
        r.run()
      } catch {
        case ex: Throwable => { ex.printStackTrace() }
      }
    }
  }

  private var already_in_stopping = false
  protected def is_stopping() = already_in_stopping
  @tailrec private def loop(): Unit = {

    val selected = selector.select(select_timeout)

    if (selected > 0) {
      val iterator = selector.selectedKeys().iterator()
      while (iterator.hasNext()) {
        val key = iterator.next()
        iterator.remove()
        process_selected_key(key)
      }
    }

    val continued = if ((!selector.isOpen()) || this.synchronized { status == STOPPING && stop_deadline < System.currentTimeMillis() }) {
      //closed
      stop_roughly()
      this.synchronized {
        status = STOPPED_ROUGHLY
        this.notifyAll()
      }
      false
    } else if (!already_in_stopping && this.synchronized { status == STOPPING }) {
      //stopping
      already_in_stopping = true
      val immediately = stop_gracefully()
      if (immediately) {
        this.synchronized {
          status = STOPPED_GRACEFULLY
          this.notifyAll()
        }
        false
      } else {
        true
      }
    } else if (already_in_stopping && !has_remaining_work()) {
      this.synchronized {
        status = STOPPED_GRACEFULLY
        this.notifyAll()
      }
      false
    } else {
      true
    }

    reap_tasks()
    reap_timed_tasks()

    if (continued) {
      before_next_loop()
      loop()
    }

  }

}