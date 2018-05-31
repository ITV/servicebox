package com.itv.servicebox

import cats.MonadError
import com.itv.servicebox.algebra.{Runner => RunnerAlg, _}
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.messages.{ContainerInfo, Container => JavaContainer}

import scala.concurrent.ExecutionContext

package object docker {
  case class ContainerWithDetails(container: JavaContainer, info: ContainerInfo)

  def runner[F[_]](portRange: Range = InMemoryServiceRegistry.DefaultPortRange,
                   client: DefaultDockerClient = DefaultDockerClient.fromEnv().build())(services: Service.Spec[F]*)(
      implicit
      I: ImpureEffect[F],
      M: MonadError[F, Throwable],
      logger: Logger[F],
      scheduler: Scheduler[F],
      ec: ExecutionContext,
      tag: AppTag): RunnerAlg[F] = {

    val registry     = new InMemoryServiceRegistry[F](portRange, logger)
    val containerCtl = new DockerContainerController[F](client, logger)
    val serviceCtl =
      new ServiceController[F](logger, registry, containerCtl, scheduler)

    new RunnerAlg[F](serviceCtl, registry)(services: _*)
  }

}
