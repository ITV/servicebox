package com.itv.servicebox.algebra

import java.nio.file.{Files, Paths}
import java.util.UUID

import cats.effect.IO
import cats.instances.future._
import org.scalatest.{FreeSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class packageTest extends FreeSpec with Matchers {
  "BindMount" - {
    "fromTmpFileContent" - {
      def testContent = s"some test content ${util.Random.nextString(1000)}"
      val targetPath  = Paths.get(s"/root/target${util.Random.nextInt(50)}")

      "persists a string into a file, creating the supplied base directory" in {
        val content1 = testContent
        val content2 = testContent

        val bindMount =
          BindMount
            .fromTmpFileContent[IO](Paths.get(System.getProperty("java.io.tmpdir"), UUID.randomUUID().toString))(
              targetPath)("example1.txt" -> content1.getBytes, "example2.txt" -> content2.getBytes())
            .unsafeRunSync()

        Files.isDirectory(bindMount.from) shouldBe true
        new String(Files.readAllBytes(bindMount.from.resolve("example1.txt"))) should ===(content1)
        new String(Files.readAllBytes(bindMount.from.resolve("example2.txt"))) should ===(content2)
      }

      "persists a string into a file without creating the base directory" in {
        val content = testContent

        val bindMount =
          BindMount
            .fromTmpFileContent[IO](Paths.get(System.getProperty("java.io.tmpdir")))(targetPath)(
              "example.txt" -> content.getBytes)
            .unsafeRunSync()

        Files.isDirectory(bindMount.from) shouldBe true
        new String(Files.readAllBytes(bindMount.from.resolve("example.txt"))) should ===(content)
      }
    }
  }
}
