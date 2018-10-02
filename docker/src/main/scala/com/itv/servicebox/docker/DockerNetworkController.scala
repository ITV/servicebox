package com.itv.servicebox.docker

import cats.FlatMap
import cats.effect.Effect
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.apply._
import cats.syntax.applicativeError._
import com.itv.servicebox.algebra._
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient.ListNetworksParam
import com.spotify.docker.client.exceptions.NetworkNotFoundException
import com.spotify.docker.client.messages.NetworkConfig

import scala.collection.JavaConverters._
import scala.util.{Success, Try}

class DockerNetworkController[F[_]](dockerClient: DefaultDockerClient, logger: Logger[F])(implicit E: Effect[F],
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
        E.delay(logger.info(s"creating network ${_networkName}")) *> E.delay(dockerClient.createNetwork(config))
      else E.delay(logger.warn(s"cannot create network ${_networkName}. It already exists!"))
    } yield ()

  private def networks: F[List[NetworkName]] =
    E.delay(
      dockerClient
        .listNetworks(ListNetworksParam.withLabel(AppTagLabel, NetworkController.networkName(tag)))
        .asScala
        .map(_.name())
        .toList)

  override def removeNetwork(): F[Unit] =
    for {
      networkExists <- networks.map(_.exists(_ == _networkName))
      _ <- if (networkExists)
        E.delay(logger.info(s"removing network '${_networkName}'")) *> removeNetwork(config.name())
      else E.delay(logger.debug(s"cannot remove network ${_networkName}. It doesn't exist!"))
    } yield ()

  private def removeNetwork(networkName: String) = E.delay(
    dockerClient.removeNetwork(config.name())
  ).recoverWith {
    case _:NetworkNotFoundException => E.unit
  }

  override def networkName: Option[NetworkName] = Some(config.name())
}
