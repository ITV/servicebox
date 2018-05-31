package com.itv.servicebox.algebra

import com.itv.servicebox.algebra.Dag.Edges
import org.scalatest.{FreeSpec, Matchers}
import cats.syntax.either._

class DagTest extends FreeSpec with Matchers {
  "Dag" - {
    "kahanSort" - {
      "sorts items in topological order" in {
        val g1: Edges[String] = Map(
          "PG"   -> Set("Srv1", "Srv2"),
          "RMQ"  -> Set("Srv1", "Srv2"),
          "Srv3" -> Set.empty,
          "Srv1" -> Set("Srv3"),
          "Srv2" -> Set("Srv3")
        )

        //from https://en.wikipedia.org/w/index.php?title=Topological_sorting&oldid=839618280#Examples
        val g2: Dag.Edges[Int] = Map(
          5  -> Set.empty,
          7  -> Set.empty,
          3  -> Set.empty,
          11 -> Set(5, 7),
          8  -> Set(7, 3),
          2  -> Set(11),
          9  -> Set(11, 8),
          10 -> Set(11, 3)
        )

        val cyclicalGraphErr = (err: Throwable) => fail(s"acyclical graph expected: ${err.getMessage}")

        val nodes1 = Dag(g1).topologicalSort.valueOr(cyclicalGraphErr)
        val nodes2 = Dag(g2).topologicalSort.valueOr(cyclicalGraphErr)

        nodes1.slice(0, 1) should ===(Vector("Srv3"))
        nodes1.slice(1, 3).toSet should ===(Set("Srv1", "Srv2"))
        nodes1.slice(3, 5).toSet should ===(Set("PG", "RMQ"))

        nodes2.slice(0, 3).toSet should ===(Set(5, 7, 3))
        nodes2.slice(3, 5).toSet should ===(Set(11, 8))
        nodes2.slice(5, 8).toSet should ===(Set(2, 9, 10))
      }
    }

    "raises an error when a circular dependency is found" in {
      val cyclic: Dag[String] = Dag(
        Map(
          "PG"   -> Set("Srv1", "Srv2"),
          "RMQ"  -> Set("Srv1", "Srv2"),
          "Srv3" -> Set.empty,
          "Srv1" -> Set("Srv2"),
          "Srv2" -> Set("Srv1")
        ))

      cyclic.topologicalSort.isLeft should ===(true)
    }
  }

}
