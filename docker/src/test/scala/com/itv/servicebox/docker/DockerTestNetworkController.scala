package com.itv.servicebox.docker

import cats.FlatMap
import cats.effect.Effect
import com.itv.servicebox.algebra._
import com.itv.servicebox.fake.TestNetworkState
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient.ListNetworksParam

import scala.collection.JavaConverters._

class DockerTestNetworkController[F[_]](client: DefaultDockerClient, logger: Logger[F])(implicit E: Effect[F],
                                                                                        M: FlatMap[F],
                                                                                        tag: AppTag)
    extends DockerNetworkController[F](client, logger)
    with TestNetworkState[F] {
  override def networks: F[List[NetworkName]] =
    E.delay(
      client
        .listNetworks(ListNetworksParam.withLabel(AppTagLabel, NetworkController.networkName(tag)))
        .asScala
        .map(_.name())
        .toList)
}
