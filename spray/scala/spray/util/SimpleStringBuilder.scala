package spray.util

final class SimpleStringBuilder(var array: Array[Char]) {

  var start = 0
  var end = 0

  def this(capacity: Int) = {
    this(new Array[Char](capacity))
  }

  private def growBy(i: Int) = {
    if (array.length - end < i) {
      val tmp = new Array[Char](array.length - start + (i - array.length + end))
      System.arraycopy(array, start, tmp, 0, end - start)

      array = tmp
      end = end - start
      start = 0
    }
  }

  def append(c: Char) = {
    growBy(1)
    array(end) = c
    end = end + 1
    this
  }

  def append(cs: CharSequence) = {
    growBy(cs.length())
    for (i <- 1 until cs.length()) {
      array(end) = cs.charAt(i)
      end = end + 1
    }
    this
  }

  def appendAsciistring(input: akka.util.ByteString, start: Int, end: Int) = {
    @scala.annotation.tailrec
    def build(ix: Int = start): this.type =
      if (ix == end) {
        this
      } else {
        append(input(ix).toChar)
        build(ix + 1)
      }

    build()
  }

  def trimFront() = {
    @scala.annotation.tailrec def front(): Unit = {
      if (start < end && array(start) == ' ') {
        start = start + 1
        front()
      }
    }
    //
    front()
  }

  def trimTail() = {
    @scala.annotation.tailrec def tail(): Unit = {
      if (end - 1 > start && array(end - 1) == ' ') {
        end = end - 1
        tail()
      }
    }
    tail()

    this
  }

  def trim() = {
    trimFront()
    trimTail()
  }

  def reset() = {
    start = 0
    end = 0
    this
  }

  override def toString() = {
    new String(array, start, end)
  }

}