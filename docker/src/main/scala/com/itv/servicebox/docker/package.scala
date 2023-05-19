package com.itv.servicebox

import cats.MonadError
import cats.effect.kernel.Sync
import com.itv.servicebox.algebra.{Runner => RunnerAlg, _}
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.messages.{ContainerInfo, Container => JavaContainer}

import scala.concurrent.ExecutionContext

package object docker {
  case class ContainerWithDetails(container: JavaContainer, info: ContainerInfo)

  private[docker] val AppTagLabel       = "com.itv.servicebox.app-ref"
  private[docker] val ServiceRefLabel   = "com.itv.servicebox.service-ref"
  private[docker] val ContainerRefLabel = "com.itv.servicebox.container-ref"
  private[docker] val AssignedName      = "com.itv.servicebox.container-name-assigned"

  def runner[F[_]](portRange: Range = InMemoryServiceRegistry.DefaultPortRange,
                   client: DefaultDockerClient = DefaultDockerClient.fromEnv().build())(services: Service.Spec[F]*)(
      implicit
      S: Sync[F],
      M: MonadError[F, Throwable],
      logger: Logger[F],
      scheduler: Scheduler[F],
      ec: ExecutionContext,
      tag: AppTag): RunnerAlg[F] = {

    val registry      = new InMemoryServiceRegistry[F](portRange, logger)
    val networkCtrl   = new DockerNetworkController[F](client, logger)
    val containerCtrl = new DockerContainerController[F](client, logger, networkCtrl.networkName)
    val serviceCtl =
      new ServiceController[F](logger, registry, containerCtrl, scheduler)

    new RunnerAlg[F](serviceCtl, networkCtrl, registry)(services: _*)
  }

}
