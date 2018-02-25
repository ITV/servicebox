package com.itv.servicebox.algebra

import cats.MonadError
import cats.syntax.flatMap._
import cats.syntax.functor._
import Service.Status

abstract class Runner[F[_]](registry: Registry[F], ctrl: Controller[F], strategy: CleanupStrategy)(
    implicit M: MonadError[F, Throwable]) {
  def setUp(spec: Service.Spec[F]): F[Service.Registered[F]] =
    for {
      _            <- ctrl.isRunning(spec).ifM(M.pure(()), ctrl.start(spec))
      maybeService <- registry.lookup(spec.id)
      service      <- maybeService.fold(registry.register(spec))(M.pure)
      _            <- registry.updateStatus(spec.id, Status.Running)
      registered = service.copy(status = Status.Running)
      _ <- ctrl.waitUntilReady(registered)
    } yield registered

  def tearDown(id: Service.Id): F[Unit] =
    for {
      _ <- registry.deregister(id)
      _ <- ctrl.stop(id)
    } yield ()
}
