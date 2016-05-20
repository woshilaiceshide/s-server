package woshilaiceshide.sserver.utility

import scala.reflect.ClassTag
import scala.annotation._

final class Node[@specialized(Int, Long, Short, Byte, Boolean, Double, Float) T](val value: T, var next: Node[T])

final class LinkedNodeQueue[@specialized(Int, Long, Short, Byte, Boolean, Double, Float) T](var head: Node[T], var tail: Node[T]) {
  var size = 0
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
    size = size + 1
  }
  def enqueue(value: T): Unit = append(new Node(value, null))
  def dequeue(): Node[T] = {
    if (null == head) {
      null
    } else {
      size = size - 1
      val tmp = head
      head = head.next
      if (head eq tail) {
        tail = null
      }
      tmp
    }
  }
}
final class LinkedNodeStack[@specialized(Int, Long, Short, Byte, Boolean, Double, Float) T](var head: Node[T], var tail: Node[T]) {
  var size = 0
  private def append(node: Node[T]): Unit = {
    if (null == tail) {
      tail = node
    } else if (head == null) {
      head = node
      node.next = tail
    } else {
      node.next = head
      head = node
    }
    size = size + 1
  }
  def push(value: T): Unit = append(new Node(value, null))
  def pop(): Node[T] = {
    if (null == tail) {
      null
    } else if (null == head) {
      size = size - 1
      val tmp = tail
      tail = null
      tmp
    } else {
      size = size - 1
      val tmp = head
      if (head.next == tail) {
        head = null
      } else {
        head = head.next
      }
      tmp
    }
  }
}

final class ArrayNodeStack[@specialized(Int, Long, Short, Byte, Boolean, Double, Float) T](capacity: Int)(implicit ctag: ClassTag[T]) {

  private val array = new Array[T](capacity)
  private var cursor: Int = 0

  def push(value: T): Boolean = {
    if (cursor < array.length) {
      array(cursor) = value
      cursor = cursor + 1
      true
    } else {
      false
    }
  }

  def pop(): Option[T] = {
    if (cursor == 0) {
      None
    } else {
      val tmp = array(cursor - 1)
      cursor = cursor - 1
      Some(tmp)
    }
  }

}

final class LinkedNodeList[@specialized(Int, Long) T](var head: Node[T], var tail: Node[T]) {

  import LinkedNodeList._

  var size = 0

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
    size = size + 1
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
  private def kick_off_and_operate_on_it_0[U](new_list: LinkedNodeList[T], first: Node[T], filter: T => Boolean, op: T => U): Unit = {
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
  def kick_off_and_operate_on_it[U](filter: T => Boolean, op: T => U): LinkedNodeList[T] = {
    val new_list = LinkedNodeList.newEmpty[T]()
    kick_off_and_operate_on_it_0(new_list, head, filter, op)
    new_list
  }

  def group_by_fitler(filter: T => Boolean): Filtered[T] = {

    val filtered = LinkedNodeList.newEmpty[T]()
    val unfiltered = kick_off_and_operate_on_it(filter, e => filtered.append(e))

    Filtered(filtered, unfiltered)
  }
}
object LinkedNodeList {
  final case class Filtered[T](filtered: LinkedNodeList[T], unfiltered: LinkedNodeList[T])
  def newEmpty[T]() = new LinkedNodeList[T](null, null)
}


