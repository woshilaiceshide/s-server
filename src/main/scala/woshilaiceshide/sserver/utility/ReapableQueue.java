package woshilaiceshide.sserver.utility;

import java.util.concurrent.atomic.*;

/**
 * multiple threads can be 'add(...)' concurrently, but only one thread can
 * 'reap()' at the same time.
 * 
 * @author woshilaiceshide
 *
 * @param <T>
 */
class ReapableQueue<T> {

	@SuppressWarnings("rawtypes")
	private static AtomicReferenceFieldUpdater<ReapableQueue, Node> head_updater = AtomicReferenceFieldUpdater
			.newUpdater(ReapableQueue.class, Node.class, "head");

	@SuppressWarnings("rawtypes")
	private static AtomicReferenceFieldUpdater<ReapableQueue, Node> tail_updater = AtomicReferenceFieldUpdater
			.newUpdater(ReapableQueue.class, Node.class, "tail");

	@SuppressWarnings("rawtypes")
	private static AtomicReferenceFieldUpdater<Node, Node> next_updater = AtomicReferenceFieldUpdater
			.newUpdater(Node.class, Node.class, "next");

	public static class Node<T> {
		public final T value;
		private volatile Node<T> next;

		Node(T value, Node<T> next) {
			this.value = value;
			this.next = next;
		}

		Node(T value) {
			this(value, null);
		}

		// please see
		// http://robsjava.blogspot.com/2013/06/a-faster-volatile.html
		void set_next(Node<T> new_next) {
			next_updater.lazySet(this, new_next);
			return;
		}

		@SuppressWarnings("unchecked")
		public Node<T> get_next() {
			return next_updater.get(this);
		}
	}

	public static class Reaped<T> {
		public Node<T> head;
		public final Node<T> tail;

		public Reaped(Node<T> head, Node<T> tail) {
			this.head = head;
			this.tail = tail;
		}

		public Node<T> get_current_and_advance() {
			if (this.head == null) {
				return null;
			} else {
				Node<T> tmp = this.head;
				if (this.head == this.tail) {
					this.head = null;
				} else {
					Node<T> next = this.head.get_next();
					while (true) {
						if (next != null)
							break;
						else
							next = this.head.get_next();
					}
				}
				return tmp;
			}

		}

	}

	private java.util.concurrent.atomic.AtomicBoolean ended = new AtomicBoolean(false);

	@SuppressWarnings("unused")
	private volatile Node<T> head = null;

	@SuppressWarnings("unused")
	private volatile Node<T> tail = null;

	@SuppressWarnings("unchecked")
	public boolean add(T value) {

		if (ended.get())
			return false;

		Node<T> new_tail = new Node<T>(value);
		Node<T> old_tail = tail_updater.getAndSet(this, new_tail);
		if (null == old_tail) {
			head_updater.set(this, new_tail);
		} else {
			old_tail.set_next(new_tail);
		}

		if (!ended.get()) {
			return true;
		} else {

			Node<T> tmp = tail_updater.get(this);
			if (tmp == null) {
				// it's reaped out.
				return true;
			} else if (tmp == new_tail) {

			} else {

			}

			return true;
		}
	}

	public Reaped<T> reap() {

		Node<T> old_head = head_updater.get(this);
		if (old_head == null) {
			return null;
		}

		Node<T> old_tail = tail_updater.getAndSet(this, null);
		if (old_tail == null) {
			throw new Error("??????");
		}

		head_updater.compareAndSet(this, old_head, null);
		return new Reaped<T>(old_head, old_tail);

	}

	public void end() {
		ended.set(true);
		// DO NOT reap here!!!
	}

}