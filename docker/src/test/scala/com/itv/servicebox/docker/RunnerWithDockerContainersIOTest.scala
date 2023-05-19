package com.itv.servicebox.docker

import cats.effect.IO
import cats.effect.kernel.Clock
import com.itv.servicebox.algebra._
import com.itv.servicebox.interpreter.{IOLogger, ioScheduler}
import com.itv.servicebox.test.{Dependencies, RunnerTest, TestData, TestEnv}
import com.spotify.docker.client.DefaultDockerClient
import org.scalatest.{Assertion, BeforeAndAfterAll}
import cats.syntax.apply._

import scala.concurrent.ExecutionContext.Implicits.global

class RunnerWithDockerContainersIOTest extends RunnerTest[IO] with BeforeAndAfterAll {

  val dockerClient = DefaultDockerClient.fromEnv.build

  val imageRegistry = new DockerImageRegistry[IO](dockerClient, IOLogger)

  val networkCtrl: DockerTestNetworkController[IO] = {
    import TestData.appTag
    new DockerTestNetworkController[IO](dockerClient, IOLogger)
  }

  val containerCtrl = {
    import TestData.appTag
    new DockerContainerController(dockerClient, IOLogger, networkCtrl.networkName)
  }

  override def dependencies(implicit tag: AppTag): Dependencies[IO] =
    new Dependencies(IOLogger, imageRegistry, networkCtrl, containerCtrl, ioScheduler(IOLogger, implicitly[Clock[IO]]))

  override def withServices(testData: TestData[IO])(f: TestEnv[IO] => IO[Assertion])(implicit tag: AppTag) =
    containerCtrl.removeContainers *> networkCtrl.removeNetwork *> super.withServices(testData)(f)
}
