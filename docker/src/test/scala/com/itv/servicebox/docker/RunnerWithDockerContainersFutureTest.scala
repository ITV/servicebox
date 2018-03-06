package com.itv.servicebox.docker

import cats.instances.future._
import cats.syntax.flatMap._
import com.itv.servicebox.algebra._
import com.itv.servicebox.interpreter.FutureLogger
import com.itv.servicebox.test.{Dependencies, RunnerTest, TestData, SingleThreadExecutionContext}
import com.spotify.docker.client.DefaultDockerClient
import org.scalatest.{Assertion, BeforeAndAfterAll}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class RunnerWithDockerContainersFutureTest extends RunnerTest[Future] with BeforeAndAfterAll {
  val dockerClient = DefaultDockerClient.fromEnv.build

  val logger        = new FutureLogger
  val imageRegistry = new DockerImageRegistry[Future](dockerClient, logger)

  val containerController = {
    //TODO: this is nasty! fix appTag..
    import TestData.appTag
    new DockerContainerController(dockerClient, imageRegistry, logger)
  }

  override def dependencies(implicit tag: AppTag): Dependencies[Future] =
    new Dependencies(logger, imageRegistry, containerController)

  override def withServices(testData: TestData[Future])(
      f: (Runner[Future], ServiceRegistry[Future], Dependencies[Future]) => Future[Assertion])(implicit tag: AppTag) =
    containerController.stopContainers >> super.withServices(testData)(f)

  override def afterAll() =
    Await.result(containerController.stopContainers, Duration.Inf)
}
