package com.itv.servicebox.algebra

import cats.MonadError
import cats.syntax.flatMap._
import cats.syntax.functor._

abstract class Runner[F[_]](ctrl: Controller[F])(implicit M: MonadError[F, Throwable]) {
  def setUp(spec: Service.Spec[F]): F[Service.Registered[F]] =
    for {
      registered <- ctrl.start(spec)
      _          <- ctrl.waitUntilReady(registered)
    } yield registered

  def tearDown(id: Service.Id): F[Unit] =
    for {
      _ <- ctrl.stop(id)
    } yield ()
}
