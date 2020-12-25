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

  val log = org.slf4j.LoggerFactory.getLogger(classOf[SelectorRunner]);

  class NotRunningException(msg: String) extends RuntimeException(msg)
  class NotInIOThreadException(msg: String) extends RuntimeException(msg)
  object NotInIOThreadException {
    def apply() = {
      new NotInIOThreadException(s"please run this method in i/o thread. the current thread is ${Thread.currentThread().getName}-${Thread.currentThread().getId}")
    }
  }

  def safe_close(x: Closeable) = try { if (null != x) x.close(); } catch { case ex: Throwable => log.warn("failed", ex) }
  def safe_op[T](x: => T) = try { x } catch { case ex: Throwable => log.warn("failed", ex) }

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
 *
 * note that all the operations on the SelectionKey should be in the i/o thread.
 */
abstract class SelectorRunner(configurator: SelectorRunnerConfigurator) {

  import SelectorRunner._

  //private val default_select_timeout = 30 * 1000
  protected var select_timeout = configurator.default_select_timeout

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
        case ex: Throwable => {
          log.warn("failed to optimize selector key set", ex)
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
  //this method is invoked in the i/o thread only.
  private def close_selector() = {
    if (null != selector) safe_close(selector); selector = null;
  }
  /**
   * this method is thread safe.
   */
  def wakeup_selector() = {
    val tmp = selector
    //wakeup() on a dead selector is fine. tested for hotspot jdk.
    if (null != tmp) selector.wakeup()
  }

  /**
   * make sure this method is invoked in the corresponding io thread.
   */
  protected def register(ch: SelectableChannel, ops: Int, att: Object): SelectionKey = {
    //for performance
    /*
    if (!is_in_io_worker_thread()) {
      throw NotInIOThreadException()
    }
    */
    if (null != selector) ch.register(selector, ops, att)
    else throw new NotRunningException("selector runner is stopping or stopped or not started.")
  }

  /**
   * make sure this method is invoked in the corresponding io thread.
   */
  protected def get_registered_size(): Int = {
    //for performance
    /*
    if (!is_in_io_worker_thread()) {
      throw NotInIOThreadException()
    }
    */
    if (null != selector) selector.keys().size
    else throw new NotRunningException("selector runner is stopping or stopped or not started.")
  }

  /**
   * make sure this method is invoked in the corresponding io thread.
   */
  protected def iterate_registered_keys(worker: SelectionKey => Unit): Unit = {
    //for performance
    /*
    if (!is_in_io_worker_thread()) {
      throw NotInIOThreadException()
    }
    */
    if (null != selector) {
      val iterator = selector.keys().iterator()
      while (iterator.hasNext()) {
        val key = iterator.next()
        safe_op { worker.apply(key) }
      }
    } else throw new NotRunningException("selector runner is stopping or stopped or not started.")

  }

  private var worker_thread: Thread = null
  def get_worker_thread() = worker_thread

  private val status = new java.util.concurrent.atomic.AtomicInteger(INITIALIZED)

  private val immediate_tasks: ReapableQueue[AnyRef] = new ReapableQueue[AnyRef]()
  /**
   * only can be posted when the runner is in 'STARTED' status.
   */
  def post_to_io_thread(task: => Unit): Boolean = post_to_io_thread(new Runnable() {
    def run() = task
  })
  /**
   * only can be posted when the runner is in 'STARTED' status.
   */
  def post_to_io_thread(task: AnyRef): Boolean = {
    if (immediate_tasks.add(task)) {
      wakeup_selector()
      true
    } else {
      false
    }
  }
  private def end_tasks() = {
    immediate_tasks.end()
  }

  protected def add_a_new_socket_channel(channel: SocketChannel): Unit

  private val task_runner = (obj: AnyRef) => {
    try {
      obj match {
        case ssc: SocketChannel => {
          if (is_stopping())
            safe_close(ssc)
          else {
            add_a_new_socket_channel(ssc)
          }

        }
        case runnable: Runnable => runnable.run()
        case _                  =>
      }
    } catch {
      case ex: Throwable => log.warn("failed", ex)
    }
  }
  private def reap_immediate_tasks(is_last_reap: Boolean) = {
    val tasks_to_do = immediate_tasks.reap(is_last_reap)
    if (null != tasks_to_do) {
      ReapableQueueUtility.foreach(tasks_to_do, task_runner)
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
        timed_tasks_to_do.foreach { x => safe_op { x.runnable.run() } }
    }
  }

  private var terminated = false
  private val lock_for_terminated = new Object
  private var when_terminated: LinkedNodeList[Runnable] = LinkedNodeList.newEmpty()
  def register_on_termination[T](code: => T) = lock_for_terminated.synchronized {
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
      tmp
    }
    termination_sinks.foreach { x => safe_op(x.run()) }
    this.synchronized {
      this.notifyAll()
    }
  }

  /**
   * some work to do right before this runner starts
   */
  protected def do_start(): Unit

  private final def stop_roughly0(): Unit = {
    log.warn("stopping roughly")
    stop_roughly()
  }

  /**
   * this runner is bad, and it should be shutdown forcibly.
   */
  protected def stop_roughly(): Unit

  private final def stop_gracefully0(): Boolean = {
    log.warn("stopping gracefully")
    stop_gracefully()
  }
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
      log.info(s"started(#${this.hashCode()})")
      true
    } catch {
      case ex: Throwable => {
        log.error("failed to start", ex)
        //also run stop_roughly()
        stop_roughly0()
        status.set(BAD)
        close_selector()
        reap_timed_tasks()
        end_tasks()
        reap_immediate_tasks(true)
        reap_terminated()
        false
      }
    } finally {
    }
  }

  private var async: Boolean = false
  def is_async = async

  def start(asynchronously: Boolean = true) = {
    log.info(s"start(#${this.hashCode()}) ${if (asynchronously) "asynchronously" else "synchronously"}")
    var continued = status.compareAndSet(INITIALIZED, STARTED)
    if (continued) {
      if (asynchronously) {
        async = true
        //worker_thread should not be assigned in the new thread's running.
        worker_thread = {
          val tmp = configurator.io_thread_factory.newThread(new Runnable {
            override def run() = if (start0()) { safe_loop() }
          })
          tmp.setName(s"sserver-selector-h${hashCode()}-t${System.currentTimeMillis()}")
          tmp
        }
        worker_thread.start()
      } else {
        async = false
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
        log.error("stopping unexpectedly", ex)
        stop_roughly0()
        status.set(STOPPED_ROUGHLY)
      }
    } finally {
      close_selector()
      reap_timed_tasks()
      end_tasks()
      reap_immediate_tasks(true)
      reap_terminated()
      log.info(s"stopped(#${this.hashCode()})")
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
          if (-1 == timeout) {
            stop_deadline = -1
          } else {
            stop_deadline = Math.max(0, timeout) + System.currentTimeMillis()
          }
        }
      }
    } else this.synchronized {
      if (status.compareAndSet(STARTED, STOPPING)) {
        if (-1 == timeout) {
          stop_deadline = -1
        } else {
          stop_deadline = Math.max(0, timeout) + System.currentTimeMillis()
        }
        safe_op { wakeup_selector() }
        safe_op { worker_thread.interrupt() }
      }
    }
  }
  def join(timeout: Long) = {
    if (worker_thread != Thread.currentThread()) {
      worker_thread.join(timeout)
    }
  }

  def get_status() = status.get()

  //a just normal field, not labeled as volatile
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
      //stopping
      already_in_stopping = true
      //closed
      stop_roughly0()
      status.set(STOPPED_ROUGHLY)
      false
    } else if (!already_in_stopping && current_status == STOPPING) {
      //stopping
      already_in_stopping = true
      val immediately = stop_gracefully0()
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

    reap_immediate_tasks(false)
    reap_timed_tasks()

    if (continued) {
      before_next_loop()
      loop()
    }

  }

}