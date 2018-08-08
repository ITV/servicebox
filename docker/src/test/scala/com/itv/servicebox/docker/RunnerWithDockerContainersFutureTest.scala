package com.itv.servicebox.docker

import java.util.concurrent.{Executors, ScheduledExecutorService}

import cats.instances.future._
import com.itv.servicebox.algebra._
import com.itv.servicebox.interpreter.FutureLogger
import com.itv.servicebox.test.{Dependencies, RunnerTest, TestData, TestEnv}
import com.spotify.docker.client.DefaultDockerClient
import cats.syntax.apply._
import org.scalatest.{Assertion, BeforeAndAfterAll}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class RunnerWithDockerContainersFutureTest extends RunnerTest[Future] with BeforeAndAfterAll {
  val dockerClient = DefaultDockerClient.fromEnv.build

  implicit val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(3)
  implicit val logger                             = new FutureLogger
  val imageRegistry                               = new DockerImageRegistry[Future](dockerClient, logger)

  val networkCtrl = {
    import TestData.appTag
    new DockerTestNetworkController(dockerClient, logger)
  }

  val containerCtrl = {
    //TODO: this is nasty! fix appTag..
    import TestData.appTag
    new DockerContainerController(dockerClient, logger, networkCtrl.networkName)
  }

  override def dependencies(implicit tag: AppTag): Dependencies[Future] =
    new Dependencies(logger, imageRegistry, networkCtrl, containerCtrl, Scheduler.futureScheduler)

  override def withServices(testData: TestData[Future])(f: TestEnv[Future] => Future[Assertion])(implicit tag: AppTag) =
    containerCtrl.removeContainers *> networkCtrl.removeNetwork *> super.withServices(testData)(f)
}
