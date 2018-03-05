package com.itv.servicebox.docker

import cats.effect.IO
import com.itv.servicebox.algebra._
import com.itv.servicebox.interpreter.IOLogger
import com.itv.servicebox.test.{Dependencies, RunnerTest, TestData}
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient.ListContainersParam
import cats.syntax.flatMap._
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
    val appTag = services.headOption.map(_.tag).getOrElse(TestData.appTag)
    import DockerContainerController._
    import scala.collection.JavaConverters._
    import cats.syntax.show._
    import cats.instances.list._
    import cats.syntax.traverse._
    import cats.syntax.functor._

    IO(
      dockerClient.listContainers(ListContainersParam.withStatusRunning(),
                                  ListContainersParam.withLabel(AppNameLabel, appTag.show)))
      .map(_.asScala.toList.map(_.id()))
      .flatMap(ids => ids.traverse(id => IO(dockerClient.killContainer(id))))
      .void

  }

}
