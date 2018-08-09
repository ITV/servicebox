package com.itv.servicebox.docker

import cats.MonadError
import cats.effect.Effect
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._
import com.itv.servicebox.algebra
import com.itv.servicebox.algebra.Container.Matcher
import com.itv.servicebox.algebra.ContainerController.ContainerGroups
import com.itv.servicebox.algebra._
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient.{ListContainersParam, RemoveContainerParam}
import com.spotify.docker.client.messages.HostConfig.Bind
import com.spotify.docker.client.messages.{Container => JavaContainer, _}

import scala.collection.JavaConverters._
import scala.collection.mutable

class DockerContainerController[F[_]](
    dockerClient: DefaultDockerClient,
    logger: Logger[F],
    network: Option[NetworkName])(implicit E: Effect[F], M: MonadError[F, Throwable], tag: AppTag)
    extends algebra.ContainerController[F](new DockerImageRegistry[F](dockerClient, logger), logger, network) {

  override def containerGroups(service: Service.Registered[F]) =
    for {
      runningContainersByImageName <- runningContainersByImageName(service.toSpec)
      results <- service.containers.foldM(List.empty[Matcher.Result[ContainerWithDetails]]) {
        case (acc, c) =>
          runningContainersByImageName
            .getOrElse(c.imageName, Nil)
            .traverse { javaContainer =>
              E.delay(dockerClient.inspectContainer(javaContainer.id())).map { info =>
                ContainerMatcher(ContainerWithDetails(javaContainer, info), c)
              }
            }

      }

    } yield {
      ContainerGroups(
        results.filter(_.isSuccess).map(_.actual),
        results.collect {
          case Matcher.Mismatch(_, _, actual, diff) =>
            actual -> diff
        }
      )

    }

  def runningContainersByImageName(spec: Service.Spec[F]): F[Map[String, List[JavaContainer]]] =
    E.delay(dockerClient.listContainers(queryParams(spec.ref, None): _*))
      .map(_.asScala.toList.groupBy(_.image()))

  override protected def startContainer(serviceRef: Service.Ref, container: Container.Registered): F[Unit] = {
    val config = containerConfig(serviceRef, container)

    for {
      res <- E.delay(
        container.name
          .fold(dockerClient.createContainer(config))(name => dockerClient.createContainer(config, name)))
      _ <- E.delay(dockerClient.startContainer(res.id()))

    } yield ()
  }

  override def removeContainer(serviceRef: Service.Ref, containerRef: Container.Ref): F[Unit] =
    forceRm(queryParams(serviceRef, containerRef.some): _*)

  private[docker] def removeContainers =
    forceRm(ListContainersParam.withLabel(AppTagLabel, tag.show), ListContainersParam.allContainers())

  private def forceRm(params: ListContainersParam*): F[Unit] =
    for {
      containers <- E
        .delay(dockerClient.listContainers(params: _*))
        .map(_.asScala.toList)
      _ <- logger.warn(s"removing containers: ${containers.map(_.id())}")
      _ <- containers.traverse(c => E.delay(dockerClient.removeContainer(c.id, RemoveContainerParam.forceKill())))

    } yield ()

  private def queryParams(serviceRef: Service.Ref, containerRef: Option[Container.Ref]): Seq[ListContainersParam] =
    containerLabels(serviceRef, containerRef, assignedName = None).map {
      case (k, v) => ListContainersParam.withLabel(k, v)
    }.toSeq :+ ListContainersParam.allContainers()

  private def containerLabels(serviceRef: Service.Ref,
                              containerRef: Option[Container.Ref],
                              assignedName: Option[String]): Map[String, String] =
    Map(ServiceRefLabel -> serviceRef.show, AppTagLabel -> tag.show) ++ containerRef
      .map(ref => Map(ContainerRefLabel -> ref.show))
      .getOrElse(Map.empty) ++ assignedName.fold(Map.empty[String, String])(name => Map(AssignedName -> name))

  private def containerConfig(serviceRef: Service.Ref, container: Container.Registered): ContainerConfig = {
    val pbs = (mutable.Map.empty[Int, Int] ++ container.portMappings).map {
      case (hostPort, containerPort) =>
        //TODO: introduce a more granular mechanism to control whether to bind UDP/TCP ports
        s"$containerPort/tcp" -> List(PortBinding.of("0.0.0.0", hostPort.toString)).asJava
    }.asJava

    val bindMounts = container.mounts.fold(List.empty[BindMount])(_.toList).map { mb =>
      Bind.builder().from(mb.from.toAbsolutePath.toString).to(mb.to.toAbsolutePath.toString).build()
    }

    val labels = mutable.Map.empty[String, String] ++ containerLabels(serviceRef, container.ref.some, container.name)

    val hostConfig = {
      val config = HostConfig
        .builder()
        .portBindings(pbs)
        .appendBinds(bindMounts: _*)

      network.fold(config)(config.networkMode).build()
    }

    val config =
      ContainerConfig
        .builder()
        .labels(labels.asJava)
        .env(container.env.map { case (k, v) => s"$k=$v" }.toList.asJava)
        .hostConfig(hostConfig)
        .exposedPorts(pbs.keySet())
        .image(container.imageName)

    container.command.fold(config)(nel => config.cmd(nel.toList.asJava)).build()
  }
}
