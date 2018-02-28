package com.itv.servicebox.interpreter

import com.itv.servicebox.algebra
import algebra._
import cats.MonadError
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.messages.{Container => JavaContainer, _}
import com.spotify.docker.client.DockerClient.ListContainersParam
import scala.collection.JavaConverters._

class DockerController[F[_]](registry: Registry[F])(implicit M: MonadError[F, Throwable])
    extends algebra.ContainerController[F] {

  import cats.implicits._

  private val AppNameLabel = "com.itv.servicebox.app"
  private val client       = DefaultDockerClient.fromEnv.build

  override def containersFor(spec: Service.Spec[F]) =
    //- fetch list of running containers filtering by service label
    //
    //- match each list item against containers in spec grouping by: image-id, port-mapping
    //- destroy remaining containers
    //
    //- start missing containers
    ???

  private def runningContainers(spec: Service.Registered[F]) =
    M.point(
        client.listContainers(ListContainersParam.withStatusRunning(),
                              ListContainersParam.withLabel(AppNameLabel, spec.tag.show)))
      .map(_.asScala)

  override def startContainer(serviceSpec: Service.Spec[F], ref: Container.Ref): F[Unit] = ???

  override def stopContainer(sRef: Service.Ref, spec: Container.Registered): F[Unit] = ???
}

object DockerController {
  import scala.collection.mutable
  import cats.instances.option._
  import cats.syntax.apply._

  def containerConfig(c: Container.Registered, appLabel: AppTag): ContainerConfig = {
    val bindings = (mutable.Map.empty[Int, Int] ++ c.portMappings).map {
      case (hostPort, containerPort) =>
        s"$containerPort/tcp" -> List(PortBinding.of("", hostPort.toString)).asJava
    }.asJava

    val hostConfig = HostConfig.builder().portBindings(bindings).build()
    val labels     = mutable.Map.empty[String, String] ++ Map(appLabel.org -> appLabel.appName)

    ContainerConfig
      .builder()
      .labels(labels.asJava)
      .env(c.env.map { case (k, v) => s"$k=$v" }.toList.asJava)
      .hostConfig(hostConfig)
      .image(c.imageName)
      .build()
  }
}
