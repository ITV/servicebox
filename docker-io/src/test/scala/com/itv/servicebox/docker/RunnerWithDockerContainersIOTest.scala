package com.itv.servicebox.docker

import cats.effect.IO
import com.itv.servicebox.algebra._
import com.itv.servicebox.interpreter.IOLogger
import com.itv.servicebox.test.{Dependencies, RunnerTest, TestData}
import com.spotify.docker.client.DefaultDockerClient
import cats.syntax.flatMap._
import org.scalatest.{Assertion, BeforeAndAfterAll}
import com.itv.servicebox.interpreter.{ioEffect, ioScheduler}
import scala.concurrent.ExecutionContext.Implicits.global

class RunnerWithDockerContainersIOTest extends RunnerTest[IO] with BeforeAndAfterAll {

  val dockerClient = DefaultDockerClient.fromEnv.build

  implicit val logger = IOLogger

  val imageRegistry = new DockerImageRegistry[IO](dockerClient, logger)

  def containerController = {
    import TestData.appTag
    new DockerContainerController(dockerClient, logger)
  }

  override def dependencies(implicit tag: AppTag): Dependencies[IO] =
    new Dependencies(logger, imageRegistry, containerController, ioScheduler)

  override def withServices(testData: TestData[IO])(
      f: (Runner[IO], ServiceRegistry[IO], Dependencies[IO]) => IO[Assertion])(implicit tag: AppTag) =
    containerController.stopContainers >> super.withServices(testData)(f)

  override def afterAll =
    containerController.stopContainers.unsafeRunSync()
}
