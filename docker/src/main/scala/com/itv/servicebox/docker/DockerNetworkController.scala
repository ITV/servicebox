package com.itv.servicebox.docker

import cats.FlatMap
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.apply._
import com.itv.servicebox.algebra._
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient.ListNetworksParam
import com.spotify.docker.client.messages.NetworkConfig

import scala.collection.JavaConverters._

class DockerNetworkController[F[_]](dockerClient: DefaultDockerClient, logger: Logger[F])(implicit I: ImpureEffect[F],
                                                                                          M: FlatMap[F],
                                                                                          tag: AppTag)
    extends NetworkController[F] {

  private val _networkName = NetworkController.networkName(tag)

  private val config =
    NetworkConfig
      .builder()
      .name(_networkName)
      .attachable(true)
      .driver("bridge")
      .labels(Map(AppTagLabel -> _networkName).asJava)
      .build()

  override def createNetwork: F[Unit] =
    for {
      networkExists <- networks.map(_.exists(_ == _networkName))
      _ <- if (!networkExists)
        I.lift(logger.info(s"creating network ${_networkName}")) *> I.lift(dockerClient.createNetwork(config))
      else I.lift(logger.warn(s"cannot create network ${_networkName}. It already exists!"))
    } yield ()

  private def networks: F[List[NetworkName]] =
    I.lift(
      dockerClient
        .listNetworks(ListNetworksParam.withLabel(AppTagLabel, NetworkController.networkName(tag)))
        .asScala
        .map(_.name())
        .toList)

  override def removeNetwork(): F[Unit] = I.unit
  for {
    networkExists <- networks.map(_.exists(_ == _networkName))
    _ <- if (networkExists)
      I.lift(logger.info(s"removing network '${_networkName}'")) *> I.lift(dockerClient.removeNetwork(config.name()))
    else I.lift(logger.debug(s"cannot remove network ${_networkName}. It doesn't exist!"))
  } yield ()

  override def networkName: Option[NetworkName] = Some(config.name())
}
