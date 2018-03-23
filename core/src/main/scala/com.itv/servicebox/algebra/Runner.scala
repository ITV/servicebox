package com.itv.servicebox.algebra

import cats.Monad
import cats.syntax.flatMap._
import cats.instances.list._
import cats.instances.option._
import cats.syntax.foldable._
import cats.syntax.functor._

import scala.concurrent.ExecutionContext

class Runner[F[_]](ctrl: ServiceController[F], registry: ServiceRegistry[F])(services: Service.Spec[F]*)(
    implicit M: Monad[F],
    tag: AppTag) {
  def setUp(implicit ec: ExecutionContext): F[List[Service.Registered[F]]] =
    services.toList.foldM(List.empty[Service.Registered[F]])((acc, s) => setUpSpec(s).map(_ :: acc))

  def tearDown(implicit ec: ExecutionContext): F[Unit] =
    services.toList.foldM(())((_, s) => tearDownSpec(s).void)

  private def setUpSpec(spec: Service.Spec[F])(implicit ec: ExecutionContext): F[Service.Registered[F]] =
    for {
      registered <- ctrl.start(spec)
      _          <- ctrl.waitUntilReady(registered)
    } yield registered

  private def tearDownSpec(spec: Service.Spec[F]): F[Unit] =
    for {
      maybeRegistered <- registry.lookup(spec)
      _               <- maybeRegistered.fold(M.unit)(ctrl.stop)
    } yield ()
}
