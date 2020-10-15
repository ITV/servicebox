package com.itv.servicebox.fake

import java.util.concurrent.atomic.AtomicReference

import cats.MonadError
import cats.effect.Effect
import com.itv.servicebox.algebra.{NetworkController => NetworkControllerAlg, _}
import cats.syntax.show._

trait TestNetworkState[F[_]] { self: NetworkControllerAlg[F] =>
  def networks: F[List[NetworkName]]
}

object TestNetworkController {
  def apply[F[_]](implicit tag: AppTag,
                  E: Effect[F],
                  M: MonadError[F, Throwable],
                  logger: Logger[F]): TestNetworkController[F] =
    new NetworkControllerAlg[F] with TestNetworkState[F] {

      val networksCreated = new AtomicReference[Set[String]](Set.empty)

      private val _networkName = NetworkControllerAlg.networkName(tag)

      override val networkName = Some(_networkName)

      override def networks: F[List[NetworkName]] =
        E.delay(networksCreated.get().toList)

      override def createNetwork: F[Unit] = {
        logger.info(s"creating network: ${_networkName}")
        E.delay(networksCreated.getAndUpdate(_ + _networkName))
      }

      override def removeNetwork: F[Unit] =
        E.delay {
          logger.info(s"removing networks ${_networkName}")
          networksCreated.getAndUpdate(_ - _networkName)
        }
    }

}
