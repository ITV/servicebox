package com.itv.servicebox.test.interpreter

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.concurrent.Executors

import scala.collection.JavaConverters._
import com.itv.servicebox.algebra.{Scheduler, Service}
import com.itv.servicebox.interpreter.FutureLogger
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.{AsyncFreeSpec, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Future, TimeoutException}
import scala.language.postfixOps

class FutureSchedulerTest extends AsyncFreeSpec with Matchers with StrictLogging {
  implicit val executor     = Executors.newScheduledThreadPool(1)
  implicit val futureLogger = new FutureLogger()

  val tmpDirPath = Paths.get(System.getProperty("java.io.tmpdir")).resolve("servicebox")
  val interval   = 10.millis
  val timeout    = 250.millis

  def rmTmpDir = Future {
    if (Files.exists(tmpDirPath)) {
      logger.info(s"deleting temp folder in ${tmpDirPath}")
      Files.delete(tmpDirPath)
      ()
    } else ()
  }

  def tryCountFiles(implicit scheduler: Scheduler[Future]) =
    scheduler
      .retry(() => Future(Files.newDirectoryStream(tmpDirPath)), interval, timeout, "some test service")
      .map(_.iterator().asScala.size)

  "retry" - {
    "raises an error if the specified time out has expired" in {
      recoverToSucceededIf[TimeoutException](rmTmpDir.flatMap(_ => tryCountFiles))
    }
    "retries and completes as soon as an attempt succeeds" in {
      rmTmpDir
        .flatMap(_ => tryCountFiles)
        .zip(rmTmpDir.flatMap(_ =>
          Future {
            Thread.sleep(interval.toMillis)
            Files.createDirectories(tmpDirPath)
        }))
        .map(_._1 should ===(0))
    }
  }
}
