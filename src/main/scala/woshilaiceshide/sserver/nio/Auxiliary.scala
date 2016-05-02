package woshilaiceshide.sserver.nio

import scala.annotation._

/**
 * some private methods and data structures, which are dedicated for this project's efficiency.
 */
private[nio] object Auxiliary {

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

  final case class TimedTask(when_to_run: Long, runnable: Runnable)

  final class Node[@specialized(Int, Long) T](val value: T, var next: Node[T])
  final class LinkedList[@specialized(Int, Long) T](var head: Node[T], var tail: Node[T]) {

    import LinkedList._

    private def append(node: Node[T]): Unit = {
      if (null == head) {
        head = node
      } else if (null == tail) {
        head.next = node
        tail = node
      } else {
        tail.next = node
        tail = node
      }
    }
    def append(value: T): Unit = append(new Node(value, null))

    def isEmpty = head == null

    @tailrec
    private def traverse[U](first: Node[T], op: T => U): Unit = {
      if (null != first) {
        op(first.value)
        traverse(first.next, op)
      }
    }
    def foreach[U](op: T => U) = traverse(head, op)

    @tailrec
    private def kick_off_and_operate_on_it_0[U](new_list: LinkedList[T], first: Node[T], filter: T => Boolean, op: T => U): Unit = {
      if (null != first) {
        if (filter(first.value)) {
          op(first.value)
        } else {
          new_list.append(first.value)
        }
        kick_off_and_operate_on_it_0(new_list, first.next, filter, op)
      }
    }
    //traverse the list, if the element is wanted, then operate on it, 
    //or append it to the new list, which is returned last and is full of the unwanted elements.
    def kick_off_and_operate_on_it[U](filter: T => Boolean, op: T => U): LinkedList[T] = {
      val new_list = LinkedList.newEmpty[T]()
      kick_off_and_operate_on_it_0(new_list, head, filter, op)
      new_list
    }

    def group_by_fitler(filter: T => Boolean): Filtered[T] = {

      val filtered = LinkedList.newEmpty[T]()
      val unfiltered = kick_off_and_operate_on_it(filter, e => filtered.append(e))

      Filtered(filtered, unfiltered)
    }
  }
  object LinkedList {
    final case class Filtered[T](filtered: LinkedList[T], unfiltered: LinkedList[T])
    def newEmpty[T]() = new LinkedList[T](null, null)
  }

}