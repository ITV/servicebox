package com.itv.servicebox.docker

import cats.effect.IO
import cats.syntax.flatMap._
import com.itv.servicebox.algebra._
import com.itv.servicebox.interpreter.IOLogger
import com.itv.servicebox.test.{Dependencies, RunnerTest, TestData}
import com.spotify.docker.client.DefaultDockerClient
import org.scalatest.Assertion

class RunnerWithDockerContainersTest extends RunnerTest {
  val dockerClient = DefaultDockerClient.fromEnv.build

  val imageRegistry = new DockerImageRegistry[IO](dockerClient, IOLogger)

  val containerController =
    new DockerContainerController[IO](dockerClient, imageRegistry, IOLogger)

  override val dependencies: Dependencies[IO] =
    Dependencies(imageRegistry, containerController)

  override def withServices(testData: TestData[IO])(
      f: (Runner[IO], ServiceRegistry[IO], Dependencies[IO]) => IO[Assertion]) =
    cleanUpContainers(testData.services) >> super.withServices(testData)(f)

  private def cleanUpContainers(services: List[Service.Spec[IO]]): IO[Unit] = {
    import cats.instances.list._
    import cats.syntax.foldable._
    import cats.syntax.traverse._
    import cats.syntax.functor._

    services
      .foldMapM(spec => containerController.runningContainersByImageName(spec).map(_.values.toList.flatten))
      .flatMap(javaContainers => javaContainers.map(_.id()).traverse(id => IO(dockerClient.killContainer(id))))
      .void

  }

}
