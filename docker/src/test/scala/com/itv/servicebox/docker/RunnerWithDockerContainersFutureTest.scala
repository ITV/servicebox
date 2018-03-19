package com.itv.servicebox.docker

import java.util.concurrent.{Executors, ScheduledExecutorService}

import cats.instances.future._
import cats.syntax.flatMap._
import com.itv.servicebox.algebra._
import com.itv.servicebox.interpreter.FutureLogger
import com.itv.servicebox.test.{Dependencies, RunnerTest, SingleThreadExecutionContext, TestData}
import com.spotify.docker.client.DefaultDockerClient
import org.scalatest.{Assertion, BeforeAndAfterAll}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class RunnerWithDockerContainersFutureTest extends RunnerTest[Future] with BeforeAndAfterAll {
  val dockerClient = DefaultDockerClient.fromEnv.build

  implicit val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
  implicit val logger                             = new FutureLogger
  val imageRegistry                               = new DockerImageRegistry[Future](dockerClient, logger)

  val containerController = {
    //TODO: this is nasty! fix appTag..
    import TestData.appTag
    new DockerContainerController(dockerClient, imageRegistry, logger)
  }

  override def dependencies(implicit tag: AppTag): Dependencies[Future] =
    new Dependencies(logger, imageRegistry, containerController, Scheduler.futureScheduler)

  override def withServices(testData: TestData[Future])(
      f: (Runner[Future], ServiceRegistry[Future], Dependencies[Future]) => Future[Assertion])(implicit tag: AppTag) =
    containerController.stopContainers >> super.withServices(testData)(f)

  override def afterAll() =
    Await.result(containerController.stopContainers, Duration.Inf)
}
