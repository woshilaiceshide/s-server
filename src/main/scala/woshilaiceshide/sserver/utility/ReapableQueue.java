package woshilaiceshide.sserver.utility;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * multiple threads can 'add(...)' concurrently, but only one thread may
 * 'reap()' and/or 'end()' at the same time.
 *
 * @param <T>
 * @author woshilaiceshide
 */
public class ReapableQueue<T> {

    //@SuppressWarnings("rawtypes")
    //private static AtomicReferenceFieldUpdater<ReapableQueue, Node> head_updater = AtomicReferenceFieldUpdater.newUpdater(ReapableQueue.class, Node.class, "head");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<ReapableQueue, Node> tail_updater = AtomicReferenceFieldUpdater
            .newUpdater(ReapableQueue.class, Node.class, "tail");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<Node, Node> next_updater = AtomicReferenceFieldUpdater
            .newUpdater(Node.class, Node.class, "next");

    public static class ReapException extends Exception {
        private static final long serialVersionUID = 1L;

        public ReapException(String msg) {
            super(msg);
        }
    }

    public static final byte SIGNAL_NORMAL = 0;
    public static final byte SIGNAL_FAKE_HEAD = 1;
    public static final byte SIGNAL_END = 2;

    public static final class Node<T> {
        public final T value;
        volatile Node<T> next;
        public final byte signal;

        Node(T value, Node<T> next) {
            this.value = value;
            this.next = next;
            this.signal = SIGNAL_NORMAL;
        }

        Node(T value) {
            this(value, null);
        }

        Node(T value, Node<T> next, byte signal) {
            this.value = value;
            this.next = next;
            this.signal = signal;
        }

        public final boolean isNormal() {
            return this.signal == SIGNAL_NORMAL;
        }

        public final boolean isFakeHead() {
            return this.signal == SIGNAL_FAKE_HEAD;
        }

        public final boolean isEnd() {
            return this.signal == SIGNAL_END;
        }

        // please see
        // http://robsjava.blogspot.com/2013/06/a-faster-volatile.html
        void set_next(Node<T> new_next) {
            next_updater.set(this, new_next);
            return;
        }

        void set_next(Node<T> old_next, Node<T> new_next) {
            next_updater.compareAndSet(this, old_next, new_next);
            return;
        }

        @SuppressWarnings("unchecked")
        public Node<T> get_next() {
            return next_updater.get(this);
        }
    }

    final static class Reaped<T> {
        public Node<T> head;
        public final Node<T> tail;

        public Reaped(Node<T> head, Node<T> tail) {
            this.head = head;
            this.tail = tail;
        }

        Node<T> get_current_and_advance() {
            if (null == this.head) {
                return null;
            } else if (this.head != this.tail) {
                //fake head
                Node<T> next = null;
                do {
                    next = this.head.get_next();
                } while (next == null);
                Node<T> tmp = this.head;
                this.head = next;
                return tmp;
            } else /* if (this.head == this.tail) */ {
                if (this.head.isNormal()) {
                    Node<T> tmp = this.head;
                    this.head = null;
                    return tmp;
                } else {
                    return null;
                }
            }
        }

    }

    // kick false sharing
    @sun.misc.Contended
    // @SuppressWarnings("unused")
    //private volatile Node<T> head = null;
    private Node<T> head = null;

    // kick false sharing
    @sun.misc.Contended
    // @SuppressWarnings("unused")
    private volatile Node<T> tail = null;

    public ReapableQueue() {
        Node<T> head = new Node<>(null, null, SIGNAL_FAKE_HEAD);
        this.head = head;
        this.tail = head;
    }

    /**
     * this method may be invoked in multiple threads concurrently.
     *
     * @param value
     * @return if accepted and can be reaped, true is returned, otherwise false.
     */
    @SuppressWarnings("unchecked")
    public boolean add(T value) {

        Node<T> tmp = new Node<>(value, null);
        do {
            Node<T> old_tail = tail_updater.get(this);
            if (!old_tail.isEnd()) {
                if (tail_updater.compareAndSet(this, old_tail, tmp)) {
                    old_tail.set_next(tmp);
                    return true;
                } else {

                }
            } else {
                return false;
            }
        } while (true);

    }

    @SuppressWarnings("unchecked")
    /**
     * This method should be invoked in a fixed thread.
     *
     * @return
     */
    Reaped<T> reap() throws ReapException {

        Node<T> tmp = new Node<>(null, null, SIGNAL_FAKE_HEAD);
        do {
            Node<T> old_tail = tail_updater.get(this);
            if (old_tail.isFakeHead()) {
                return null;
            } else if (!old_tail.isEnd()) {
                //Node<T> old_head = head_updater.get(this);
                Node<T> old_head = this.head;
                if (tail_updater.compareAndSet(this, old_tail, tmp)) {
                    //only a single thread could modify 'this.head'
                    //head_updater.set(this, tmp);
                    this.head = tmp;
                    return new Reaped<>(old_head, old_tail);
                }
            } else {
                //Node<T> old_head = head_updater.get(this);
                Node<T> old_head = this.head;
                return new Reaped<>(old_head, old_tail);
            }
        } while (true);
    }

    @SuppressWarnings("unchecked")
    public void end() {

        Node<T> ended = new Node<>(null, null, SIGNAL_END);
        do {
            Node<T> tail = tail_updater.get(this);
            if (!tail.isEnd()) {
                if (tail_updater.compareAndSet(this, tail, ended)) {
                    tail.set_next(ended);
                    break;
                }
            } else {
                break;
            }
        } while (true);
    }

}