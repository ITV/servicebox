package com.itv.servicebox.algebra

import scala.annotation.tailrec
import scala.collection.immutable.Queue

object Dag {
  type Edges[A] = Map[A, Set[A]]

  private def kahnSort[A](edges: Edges[A]): Either[Throwable, Vector[A]] = {
    import cats.syntax.either._

    case class Kahn(queue: Queue[A], edges: Edges[A], sorted: Vector[A]) {
      def dequeue: (A, Kahn) = {
        val (node, newQueue) = queue.dequeue
        (node, copy(queue = newQueue))
      }

      def append(node: A): Kahn  = copy(sorted = sorted :+ node)
      def enqueue(node: A): Kahn = copy(queue = queue.enqueue(node))
      def withoutEdgesTo(nodeTo: A): Kahn =
        copy(edges = edges - nodeTo)
      def withIncomingEdges(nodeTo: A, nodesFrom: Set[A]): Kahn =
        copy(edges = edges.updated(nodeTo, nodesFrom))
    }
    @tailrec
    def go(s0: Kahn): Either[Throwable, Vector[A]] =
      if (s0.queue.isEmpty) {
        s0.asRight[Throwable]
          .ensureOr(s => new IllegalArgumentException(s"graph is not acyclic: ${s.edges}"))(_.edges.isEmpty)
          .map(_.sorted)

      } else {
        val (n, s) = s0.dequeue
        go(s.edges.foldLeft(s.append(n)) {
          case (s, (nodeTo, incoming)) =>
            val nodesFrom = incoming - n
            if (nodesFrom.nonEmpty)
              s.withIncomingEdges(nodeTo, nodesFrom)
            else
              s.withoutEdgesTo(nodeTo).enqueue(nodeTo)
        })
      }

    val noIncoming = edges.collect { case (n, incoming) if incoming.isEmpty => n }.toSeq
    go(Kahn(Queue(noIncoming: _*), edges.filter(_._2.nonEmpty), Vector.empty))
  }

}
case class Dag[Id](edgesIncoming: Dag.Edges[Id]) {
  def topologicalSort: Either[Throwable, List[Id]]        = Dag.kahnSort(edgesIncoming).map(_.toList)
  def reverseTopologicalSort: Either[Throwable, List[Id]] = topologicalSort.map(_.reverse)
}
