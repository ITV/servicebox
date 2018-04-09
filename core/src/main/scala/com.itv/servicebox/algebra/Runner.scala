package com.itv.servicebox.algebra

import cats.Monad
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._

import scala.concurrent.ExecutionContext

class Runner[F[_]](ctrl: ServiceController[F], registry: ServiceRegistry[F])(services: Service.Spec[F]*)(
    implicit M: Monad[F],
    tag: AppTag) {
  def setUp(implicit ec: ExecutionContext): F[List[Service.Registered[F]]] =
    services.toList.foldM(List.empty[Service.Registered[F]])((acc, s) => setUp(s).map(acc :+ _))

  def tearDown(implicit ec: ExecutionContext): F[Unit] =
    services.toList.foldM(())((_, s) => tearDown(s).void)

  private def setUp(spec: Service.Spec[F])(implicit ec: ExecutionContext): F[Service.Registered[F]] =
    for {
      registered <- ctrl.start(spec)
      _          <- ctrl.waitUntilReady(registered)
    } yield registered

  private def tearDown(spec: Service.Spec[F]): F[Unit] =
    for {
      maybeRegistered <- registry.lookup(spec)
      _               <- maybeRegistered.fold(M.unit)(ctrl.stop)
    } yield ()
}
