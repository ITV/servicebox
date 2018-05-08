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
import cats.syntax.show._
import cats.instances.list._
import cats.syntax.traverse._
import cats.syntax.option._

object DockerContainerController {
  private[docker] val AppTagLabel       = "com.itv.app-ref"
  private[docker] val ServiceRefLabel   = "com.itv.servicebox.service-ref"
  private[docker] val ContainerRefLabel = "com.itv.servicebox.container-ref"
}

class DockerContainerController[F[_]](dockerClient: DefaultDockerClient, logger: Logger[F])(implicit I: ImpureEffect[F],
                                                                                            M: MonadError[F, Throwable],
                                                                                            tag: AppTag)
    extends algebra.ContainerController[F](new DockerImageRegistry[F](dockerClient, logger), logger) {
  import DockerContainerController._

  override def containerGroups(service: Service.Registered[F]) =
    for {
      runningContainersByImageName <- runningContainersByImageName(service.toSpec)
    } yield {
      service.containers.foldLeft(ContainerGroups.Empty) { (groups, container) =>
        val (matched, notMatched) = runningContainersByImageName
          .getOrElse(container.imageName, Nil)
          .map { javaContainer =>
            val info = dockerClient.inspectContainer(javaContainer.id())
            ContainerMatcher(ContainerWithDetails(javaContainer, info), container)
          }
          .partition(_.isSuccess)

        ContainerGroups(matched.map(_.actual), notMatched.map(_.actual))
      }
    }

  def runningContainersByImageName(spec: Service.Spec[F]): F[Map[String, List[JavaContainer]]] =
    I.lift(dockerClient.listContainers(queryParams(spec.ref, None): _*))
      .map(_.asScala.toList.groupBy(_.image()))

  override protected def startContainer(serviceRef: Service.Ref, container: Container.Registered): F[Unit] =
    for {
      res <- I.lift(dockerClient.createContainer(containerConfig(serviceRef, container)))
      _   <- I.lift(dockerClient.startContainer(res.id()))

    } yield ()

  override def stopContainer(serviceRef: Service.Ref, containerRef: Container.Ref): F[Unit] =
    killContainers(queryParams(serviceRef, containerRef.some): _*)

  private[docker] def stopContainers =
    killContainers(ListContainersParam.withStatusRunning(), ListContainersParam.withLabel(AppTagLabel, tag.show))

  private def killContainers(params: ListContainersParam*): F[Unit] =
    for {
      containers <- I
        .lift(dockerClient.listContainers(params: _*))
        .map(_.asScala.toList)
      _ <- containers.traverse(c => I.lift(dockerClient.killContainer(c.id)))
      _ <- I.lift(logger.info(s"stopping ${containers.size} containers"))

    } yield ()

  private def queryParams(serviceRef: Service.Ref, containerRef: Option[Container.Ref]): Seq[ListContainersParam] =
    ListContainersParam.withStatusRunning() +: containerLabels(serviceRef, containerRef).map {
      case (k, v) => ListContainersParam.withLabel(k, v)
    }.toSeq

  private def containerLabels(serviceRef: Service.Ref, containerRef: Option[Container.Ref]): Map[String, String] =
    Map(ServiceRefLabel -> serviceRef.show, AppTagLabel -> tag.show) ++ containerRef
      .map(ref => Map(ContainerRefLabel -> ref.show))
      .getOrElse(Map.empty)

  private def containerConfig(serviceRef: Service.Ref, container: Container.Registered): ContainerConfig = {
    val bindings = (mutable.Map.empty[Int, Int] ++ container.portMappings).map {
      case (hostPort, containerPort) =>
        s"$containerPort/tcp" -> List(PortBinding.of("", hostPort.toString)).asJava
    }.asJava

    val hostConfig = HostConfig.builder().portBindings(bindings).build()
    val labels     = mutable.Map.empty[String, String] ++ containerLabels(serviceRef, container.ref.some)

    val config = ContainerConfig
      .builder()
      .labels(labels.asJava)
      .env(container.env.map { case (k, v) => s"$k=$v" }.toList.asJava)
      .hostConfig(hostConfig)
      .image(container.imageName)

    container.command.fold(config)(nel => config.cmd(nel.toList.asJava)).build()
  }
}
