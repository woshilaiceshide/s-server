package woshilaiceshide.sserver.nio

import java.nio.channels.SelectionKey
import java.util.AbstractSet
import java.util.Iterator

object SelectedKeySet {
  private val MAX_SIZE_WHEN_ENLARGE = Math.pow(2, 18).toInt
}

final class SelectedKeySet(initial_size: Int = 1024) extends AbstractSet[SelectionKey] {

  private var keys_a: Array[SelectionKey] = new Array(initial_size)
  private var size_for_key_a: Int = 0

  private var keys_b: Array[SelectionKey] = new Array(initial_size)
  private var size_for_key_b: Int = 0

  private var use_a: Boolean = true

  private def enlarge_a() = {
    val new_size = if (keys_a.length > SelectedKeySet.MAX_SIZE_WHEN_ENLARGE) {
      keys_a.length + SelectedKeySet.MAX_SIZE_WHEN_ENLARGE
    } else {
      keys_a.length << 1
    }
    val new_keys = new Array[SelectionKey](new_size)
    System.arraycopy(keys_a, 0, new_keys, 0, size_for_key_a)
    keys_a = new_keys
  }

  private def enlarge_b() = {
    val new_size = if (keys_b.length > SelectedKeySet.MAX_SIZE_WHEN_ENLARGE) {
      keys_b.length + SelectedKeySet.MAX_SIZE_WHEN_ENLARGE
    } else {
      keys_b.length << 1
    }
    val new_keys = new Array[SelectionKey](new_size)
    System.arraycopy(keys_b, 0, new_keys, 0, size_for_key_b)
    keys_b = new_keys
  }

  override def add(key: SelectionKey): Boolean = {
    if (null == key) {
      false
    } else {
      if (use_a) {
        if (size_for_key_a == keys_a.length) {
          enlarge_a()
        }
        keys_a(size_for_key_a) = key
        size_for_key_a = size_for_key_a + 1

      } else {
        if (size_for_key_b == keys_b.length) {
          enlarge_b()
        }
        keys_b(size_for_key_b) = key
        size_for_key_b = size_for_key_b + 1

      }
      true
    }
  }

  override def size: Int = {
    if (use_a) size_for_key_a
    else size_for_key_b
  }

  override def remove(o: Object): Boolean = false

  override def contains(o: Object): Boolean = false

  override def iterator(): Iterator[SelectionKey] = throw new UnsupportedOperationException()

  def flip(): Array[SelectionKey] = {
    if (use_a) {
      use_a = false
      //1. useless; 2. index should be checked
      //keys_a(size_for_key_a) = null
      size_for_key_b = 0
      keys_a
    } else {
      use_a = true
      //1. useless; 2. index should be checked
      //keys_b(size_for_key_b) = null
      size_for_key_a = 0
      keys_b
    }
  }

}