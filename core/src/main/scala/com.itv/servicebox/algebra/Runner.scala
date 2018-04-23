package com.itv.servicebox.algebra

import java.util.concurrent.TimeUnit

import cats.Monad
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import Service._

class Runner[F[_]](ctrl: ServiceController[F], registry: ServiceRegistry[F])(services: Spec[F]*)(implicit M: Monad[F],
                                                                                                 I: ImpureEffect[F],
                                                                                                 tag: AppTag) {

  def setUp(implicit ec: ExecutionContext): F[List[Registered[F]]] =
    services.toList.foldM(List.empty[Registered[F]])((acc, s) => setUp(s).map(acc :+ _))

  def setupWithRuntimeInfo(implicit ec: ExecutionContext): F[List[(Registered[F], RuntimeInfo)]] =
    services.toList.foldM(List.empty[(Registered[F], RuntimeInfo)])((acc, s) => setupWithRuntimeInfo(s).map(acc :+ _))

  def tearDown(implicit ec: ExecutionContext): F[Unit] =
    services.toList.foldM(())((_, s) => tearDown(s).void)

  private def setUp(spec: Spec[F])(implicit ec: ExecutionContext): F[Registered[F]] =
    for {
      registered <- ctrl.start(spec)
      _          <- ctrl.waitUntilReady(registered)
    } yield registered

  private def setupWithRuntimeInfo(spec: Spec[F])(implicit ec: ExecutionContext): F[(Registered[F], RuntimeInfo)] =
    for {
      _          <- tearDown(spec)
      t1         <- I.lift(System.currentTimeMillis())
      registered <- ctrl.start(spec)
      t2         <- I.lift(System.currentTimeMillis())
      _          <- ctrl.waitUntilReady(registered)
      t3         <- I.lift(System.currentTimeMillis())
    } yield {
      val setupTime      = FiniteDuration(t2 - t1, TimeUnit.MILLISECONDS)
      val readyCheckTime = FiniteDuration(t3 - t2, TimeUnit.MILLISECONDS)
      (registered, Service.RuntimeInfo(setupTime, readyCheckTime))
    }

  private def tearDown(spec: Spec[F]): F[Unit] =
    for {
      maybeRegistered <- registry.lookup(spec)
      _               <- maybeRegistered.fold(M.unit)(ctrl.stop)
    } yield ()
}
