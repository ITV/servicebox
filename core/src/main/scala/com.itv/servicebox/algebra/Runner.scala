package com.itv.servicebox.algebra

import cats.Monad
import cats.syntax.flatMap._
import cats.syntax.functor._

abstract class Runner[F[_]](ctrl: ServiceController[F])(implicit M: Monad[F]) {
  def setUp(spec: Service.Spec[F]): F[Service.Registered[F]] =
    for {
      registered <- ctrl.start(spec)
      _          <- ctrl.waitUntilReady(registered)
    } yield registered

  def tearDown(id: Service.Registered[F]): F[Unit] =
    for {
      _ <- ctrl.stop(id)
    } yield ()
}
