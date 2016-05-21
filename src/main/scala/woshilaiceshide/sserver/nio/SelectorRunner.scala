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

import woshilaiceshide.sserver.utility._

object SelectorRunner {

  private[nio] def warn(ex: Throwable, msg: String = "empty message") = {
    Console.err.print(msg)
    Console.err.print(" ")
    ex.printStackTrace(Console.err)
  }

  final case class TimedTask(when_to_run: Long, runnable: Runnable)

  val INITIALIZED = 0
  val STARTED = 1
  val STOPPING = 2
  val STOPPED_GRACEFULLY = 4
  val STOPPED_ROUGHLY = 8
  val BAD = 16

}

/**
 * read/write bytes over the accepted(connected) socket channels.
 */
abstract class SelectorRunner() {

  import SelectorRunner._

  def configurator: SelectorRunnerConfigurator

  //private val default_select_timeout = 30 * 1000
  protected var select_timeout = configurator.default_select_timeout

  import java.util.concurrent.locks.ReentrantReadWriteLock
  private val lock_for_selector = new ReentrantReadWriteLock(false)

  private var selected_keys: SelectedKeySet = null

  private def new_selector() = {
    if (configurator.try_to_optimize_selector_key_set) {
      var selector = Selector.open()
      try {
        val keys = new SelectedKeySet()
        val selector_impl_clz = Class.forName("sun.nio.ch.SelectorImpl", false, ClassLoader.getSystemClassLoader)
        if (selector_impl_clz.isAssignableFrom(selector.getClass())) {
          val f_selectedKeys = selector_impl_clz.getDeclaredField("selectedKeys")
          val f_publicSelectedKeys = selector_impl_clz.getDeclaredField("publicSelectedKeys")

          f_selectedKeys.setAccessible(true);
          f_publicSelectedKeys.setAccessible(true)

          f_selectedKeys.set(selector, keys)
          f_publicSelectedKeys.set(selector, keys)

          selected_keys = keys;
        }

      } catch {
        case _: Throwable => {
          selected_keys = null
          if (null != selector) {
            selector.close()
            selector = Selector.open()
          }
        }
      }
      selector

    } else {
      Selector.open()
    }

  }

  private var selector = new_selector()
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
  //lock_for_selector is not needed because this method is invoked in the i/o thread only.
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

  def safeClose(x: Closeable) = try { if (null != x) x.close(); } catch { case ex: Throwable => { ex.printStackTrace() } }
  def safeOp[T](x: => T) = try { x } catch { case ex: Throwable => { ex.printStackTrace() } }

  private var worker_thread: Thread = null
  def get_worker_thread() = worker_thread

  private val status = new java.util.concurrent.atomic.AtomicInteger(INITIALIZED)

  private val tasks: ReapableQueue[Runnable] = new ReapableQueue()
  /**
   * only can be posted when the runner is in 'STARTED' status.
   */
  def post_to_io_thread(task: => Unit): Boolean = post_to_io_thread(new Runnable() {
    def run() = task
  })
  /**
   * only can be posted when the runner is in 'STARTED' status.
   */
  def post_to_io_thread(task: Runnable): Boolean = {
    if (tasks.add(task)) {
      selector.wakeup()
      true
    } else {
      return false
    }
  }
  private def end_tasks() = {
    tasks.end()
  }
  private def reap_tasks(is_last_reap: Boolean) = {
    val tasks_to_do = tasks.reap(is_last_reap)
    if (null != tasks_to_do) {
      ReapableQueueUtility.foreach(tasks_to_do, safe_runner)
    }
  }

  //please invoke this method after you start the nio socket server.
  def is_in_io_worker_thread(): Boolean = Thread.currentThread() == this.worker_thread

  private val lock_for_timed_tasks = new Object
  private var timed_tasks: LinkedNodeList[TimedTask] = LinkedNodeList.newEmpty()
  def schedule_fuzzily(task: Runnable, delayInSeconds: Int) = {
    if (!configurator.enable_fuzzy_scheduler) {
      false
    } else this.synchronized {
      if (STARTED != status.get()) {
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
    if (configurator.enable_fuzzy_scheduler) {
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
  private var when_terminated: LinkedNodeList[Runnable] = LinkedNodeList.newEmpty()
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
   * process the selected key. do your best to use the given parameter named 'ready_ops', and do not user 'key.readyOps()'
   */
  protected def process_selected_key(key: SelectionKey, ready_ops: Int): Unit

  //invoked in i/o thread.
  private def start0() = {
    try {
      do_start()
      true
    } catch {
      case ex: Throwable => {
        status.set(BAD)
        close_selector()
        warn(ex)
        false
      }
    }
  }

  def start(asynchronously: Boolean = true) = {
    var continued = status.compareAndSet(INITIALIZED, STARTED)
    if (continued) {
      if (asynchronously) {
        //worker_thread should not be assigned in the new thread's running.
        worker_thread = new Thread(s"sserver-selector-h${hashCode()}-t${System.currentTimeMillis()}") {
          override def run() = if (start0()) { safe_loop() }
        }
        worker_thread.start()
      } else {
        worker_thread = Thread.currentThread()
        if (start0()) { safe_loop() }
      }
    }
  }

  private def safe_loop() {
    try {
      loop()
    } catch {
      case ex: Throwable => {
        warn(ex)
        stop_roughly()
        status.set(STOPPED_ROUGHLY)
      }
    } finally {
      close_selector()
      end_tasks()
      reap_tasks(true)
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
    if (is_in_io_worker_thread()) {
      this.synchronized {
        if (status.compareAndSet(STARTED, STOPPING)) {
          select_timeout = Math.min(Math.max(0, timeout), select_timeout)
          //stop_deadline is necessary.
          stop_deadline = Math.max(0, timeout) + System.currentTimeMillis()
        }
      }
    } else this.synchronized {
      if (status.compareAndSet(STARTED, STOPPING)) {
        stop_deadline = Math.max(0, timeout) + System.currentTimeMillis()
        safeOp { selector.wakeup() }
        safeOp { worker_thread.interrupt() }
      }
    }
  }
  def join(timeout: Long) = {
    if (worker_thread != Thread.currentThread()) {
      worker_thread.join(timeout)
    }
  }

  def getStatus() = status.get()

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

    //lock_for_selector is not needed because i am in the i/o thread.
    val selected = selector.select(select_timeout)

    if (selected > 0) {

      if (selected_keys != null) {
        val keys = selected_keys.flip()

        @tailrec def iterate_keys(keys: Array[SelectionKey], i: Int): Unit = {
          if (i == keys.length || null == keys(i)) {
            //completed
          } else {
            val key = keys(i)
            keys(i) = null
            process_selected_key(key, key.readyOps())
            iterate_keys(keys, i + 1)
          }
        }
        iterate_keys(keys, 0)

      } else {
        val iterator = selector.selectedKeys().iterator()
        while (iterator.hasNext()) {
          val key = iterator.next()
          iterator.remove()
          process_selected_key(key, key.readyOps())
        }
      }
    }

    val current_status = status.get()
    val continued = if (current_status == STOPPING && this.get_stop_deadline() /*synchronization is needed here*/ < System.currentTimeMillis()) {
      //closed
      stop_roughly()
      status.set(STOPPED_ROUGHLY)
      false
    } else if (!already_in_stopping && current_status == STOPPING) {
      //stopping
      already_in_stopping = true
      val immediately = stop_gracefully()
      if (immediately) {
        status.set(STOPPED_GRACEFULLY)
        false
      } else {
        true
      }
    } else if (already_in_stopping && !has_remaining_work()) {
      status.set(STOPPED_GRACEFULLY)
      false
    } else {
      true
    }

    reap_tasks(false)
    reap_timed_tasks()

    if (continued) {
      before_next_loop()
      loop()
    }

  }

}