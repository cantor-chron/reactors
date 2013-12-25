package org.reactress
package util



import annotation.tailrec



class UnrolledBuffer[@spec(Int, Long, Double) T](implicit val arrayable: Arrayable[T]) {
  import UnrolledBuffer._

  private[reactress] var start = new Node[T](this, arrayable.newNonNilArray(INITIAL_LENGTH))
  private[reactress] var end = start

  def isEmpty = !nonEmpty

  @tailrec private def advance() {
    if (start.startIndex >= start.endIndex) {
      if (start.next != null) {
        start = start.next
        advance()
      }
    }
  }

  def nonEmpty = {
    advance()
    start.startIndex < start.endIndex
  }

  def head = if (nonEmpty) start.array(start.startIndex) else throw new IllegalStateException("empty")

  def dequeue() = {
    val elem = head
    start.startIndex += 1
    elem
  }

  def enqueue(elem: T) = this += elem

  def +=(elem: T): this.type = {
    end += elem
    this
  }

  def foreach(f: T => Unit) {
    var node = start
    while (node != null) {
      var i = node.startIndex
      val array = node.array
      val until = node.endIndex
      while (i < until) {
        f(array(i))
        i += 1
      }
      node = node.next
    }
  }

  def clear() {
    start = new Node[T](this, arrayable.newNonNilArray(INITIAL_LENGTH))
    end = start
  }
}


object UnrolledBuffer {

  def INITIAL_LENGTH = 8
  def MAX_LENGTH = 64

  class Node[@spec(Int, Long, Double) T](val outer: UnrolledBuffer[T], val array: Array[T]) {
    private[reactress] var startIndex = 0
    private[reactress] var endIndex = 0
    private[reactress] var next: Node[T] = null

    def +=(elem: T) {
      if (endIndex < array.length) {
        array(endIndex) = elem
        endIndex += 1
      } else {
        val nlen = math.min(MAX_LENGTH, array.length * 2)
        next = new Node(outer, outer.arrayable.newNonNilArray(nlen))
        outer.end = next
        next += elem
      }
    }
  }

}