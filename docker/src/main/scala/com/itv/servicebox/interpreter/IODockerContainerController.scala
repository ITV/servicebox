package com.itv.servicebox.interpreter

import com.itv.servicebox.algebra
import algebra._
import cats.effect.IO
import com.itv.servicebox.algebra.ContainerController.ContainerGroups
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.messages.{Container => JavaContainer, _}
import com.spotify.docker.client.DockerClient.ListContainersParam
import com.itv.servicebox.docker.{ContainerAndInfo, ContainerMatcher}

import scala.collection.mutable
import scala.collection.JavaConverters._

class IODockerContainerController(imageRegistry: ImageRegistry[IO], logger: Logger[IO])
    extends algebra.ContainerController[IO](imageRegistry, logger) {

  import cats.syntax.show._

  private val AppNameLabel      = "com.itv.servicebox.app"
  private val ContainerRefLabel = "com.itv.servicebox.container-ref"
  private val dockerClient      = DefaultDockerClient.fromEnv.build

  override def containerGroups(service: Service.Registered[IO]) =
    for {
      runningContainersByImageName <- allRunningContainers(service)
    } yield {
      service.containers.foldLeft(ContainerGroups.Empty) { (groups, container) =>
        val (matched, notMatched) = runningContainersByImageName
          .getOrElse(container.imageName, Nil)
          .map { javaContainer =>
            val info = dockerClient.inspectContainer(javaContainer.id())
            ContainerMatcher(ContainerAndInfo(javaContainer, info), container)
          }
          .partition(_.isSuccess)

        ContainerGroups(matched.map(_.expected), notMatched.map(_.expected))
      }
    }

  private def allRunningContainers(spec: Service.Registered[IO]): IO[Map[String, List[JavaContainer]]] =
    IO.apply(
        dockerClient.listContainers(ListContainersParam.withStatusRunning(),
                                    ListContainersParam.withLabel(AppNameLabel, spec.tag.show)))
      .map(_.asScala.toList.groupBy(_.imageId()))

  override protected def startContainer(tag: AppTag, container: Container.Registered): IO[Unit] =
    for {
      res <- IO(dockerClient.createContainer(containerConfig(container, tag)))
      _   <- IO(dockerClient.startContainer(res.id()))

    } yield ()

  //TODO: remove service reference, as it is redundant
  override def stopContainer(appTag: AppTag, container: Container.Registered): IO[Unit] =
    for {
      containers <- IO(
        dockerClient.listContainers(
          ListContainersParam.withStatusRestarting(),
          ListContainersParam.withLabel(AppNameLabel, appTag.show),
          ListContainersParam.withLabel(ContainerRefLabel, container.ref.show)
        )).map(_.asScala.toList)
      _ <- logger.info(s"stopping ${containers.size} containers")

    } yield ()

  private def containerConfig(c: Container.Registered, tag: AppTag): ContainerConfig = {
    val bindings = (mutable.Map.empty[Int, Int] ++ c.portMappings).map {
      case (hostPort, containerPort) =>
        s"$containerPort/tcp" -> List(PortBinding.of("", hostPort.toString)).asJava
    }.asJava

    val hostConfig = HostConfig.builder().portBindings(bindings).build()
    val labels     = mutable.Map.empty[String, String] ++ Map(AppNameLabel -> tag.show, ContainerRefLabel -> c.ref.show)

    ContainerConfig
      .builder()
      .labels(labels.asJava)
      .env(c.env.map { case (k, v) => s"$k=$v" }.toList.asJava)
      .hostConfig(hostConfig)
      .image(c.imageName)
      .build()
  }
}
