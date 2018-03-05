package com.itv.servicebox.docker

import cats.MonadError
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.itv.servicebox.algebra
import com.itv.servicebox.algebra.ContainerController.ContainerGroups
import com.itv.servicebox.algebra._
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient.ListContainersParam
import com.spotify.docker.client.messages.{Container => JavaContainer, _}

import scala.collection.JavaConverters._
import scala.collection.mutable

object DockerContainerController {
  val AppNameLabel      = "com.itv.servicebox.app"
  val ContainerRefLabel = "com.itv.servicebox.container-ref"
}
class DockerContainerController[F[_]](dockerClient: DefaultDockerClient,
                                      imageRegistry: ImageRegistry[F],
                                      logger: Logger[F])(implicit I: ImpureEffect[F], M: MonadError[F, Throwable])
    extends algebra.ContainerController[F](imageRegistry, logger) {
  import DockerContainerController._

  import cats.syntax.show._

  override def containerGroups(service: Service.Registered[F]) =
    for {
      runningContainersByImageName <- runningContainersByImageName(service.toSpec)
    } yield {
      service.containers.foldLeft(ContainerGroups.Empty) { (groups, container) =>
        val (matched, notMatched) = runningContainersByImageName
          .getOrElse(container.imageName, Nil)
          .map { javaContainer =>
            val info = dockerClient.inspectContainer(javaContainer.id())
            ContainerMatcher(ContainerAndInfo(javaContainer, info), container.running)
          }
          .partition(_.isSuccess)

        ContainerGroups(matched.map(_.expected), notMatched.map(_.expected))
      }
    }

  def runningContainersByImageName(spec: Service.Spec[F]): F[Map[String, List[JavaContainer]]] =
    I.lift(
        dockerClient.listContainers(ListContainersParam.withStatusRunning(),
                                    ListContainersParam.withLabel(AppNameLabel, spec.tag.show)))
      .map(_.asScala.toList.groupBy(_.image()))

  override protected def startContainer(tag: AppTag, container: Container.Registered): F[Unit] =
    for {
      res <- I.lift(dockerClient.createContainer(containerConfig(container, tag)))
      _   <- I.lift(dockerClient.startContainer(res.id()))

    } yield ()

  //TODO: remove service reference, as it is redundant
  override def stopContainer(appTag: AppTag, container: Container.Registered): F[Unit] =
    for {
      containers <- I
        .lift(
          dockerClient.listContainers(
            ListContainersParam.withStatusRestarting(),
            ListContainersParam.withLabel(AppNameLabel, appTag.show),
            ListContainersParam.withLabel(ContainerRefLabel, container.ref.show)
          ))
        .map(_.asScala.toList)
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
