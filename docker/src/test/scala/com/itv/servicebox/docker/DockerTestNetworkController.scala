package com.itv.servicebox.docker

import cats.effect.kernel.Sync
import com.itv.servicebox.algebra._
import com.itv.servicebox.fake.TestNetworkState
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient.ListNetworksParam

import scala.jdk.CollectionConverters._

class DockerTestNetworkController[F[_]](client: DefaultDockerClient, logger: Logger[F])(implicit S: Sync[F],
                                                                                        tag: AppTag)
    extends DockerNetworkController[F](client, logger)
    with TestNetworkState[F] {
  override def networks: F[List[NetworkName]] =
    S.delay(
      client
        .listNetworks(ListNetworksParam.withLabel(AppTagLabel, NetworkController.networkName(tag)))
        .asScala
        .map(_.name())
        .toList)
}
