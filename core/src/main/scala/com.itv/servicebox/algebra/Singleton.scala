package com.itv.servicebox.algebra

import java.util.concurrent.atomic.AtomicReference

import cats.MonadError

abstract class Singleton[F[_]](service: Service.Spec[F]*)(implicit M: MonadError[F, Throwable], I: ImpureEffect[F]) {
  val runner: AtomicReference[Option[Runner[F]]] =
    new AtomicReference[Option[Runner[F]]](None)

  def runnerInstance: Runner[F]
}
