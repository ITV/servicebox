package com.itv.servicebox.algebra

import java.util.concurrent.TimeUnit

import cats.MonadError

import cats.syntax.show._
import cats.syntax.traverse._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.instances.all._

import Service._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

class Runner[F[_]](ctrl: ServiceController[F], registry: ServiceRegistry[F])(serviceSeq: Spec[F]*)(
    implicit M: MonadError[F, Throwable],
    I: ImpureEffect[F],
    tag: AppTag) {

  private val services = serviceSeq.toList

  def setUp(implicit ec: ExecutionContext): F[ServicesByRef[F]] =
    for {
      services <- servicesInReverseTopologicalOrder
      //TODO: add monoidK instance
      registered <- services.foldM(ServicesByRef.empty[F])((acc, spec) => setUp(spec, acc).map(acc + _))
    } yield registered

  def setupWithRuntimeInfo(implicit ec: ExecutionContext): F[List[(Registered[F], RuntimeInfo)]] =
    services.foldM(List.empty[(Registered[F], RuntimeInfo)])((acc, s) => setupWithRuntimeInfo(s).map(acc :+ _))

  def tearDown(implicit ec: ExecutionContext): F[Unit] =
    services.foldM(())((_, s) => tearDown(s).void)

  private def servicesInReverseTopologicalOrder: F[List[Service.Spec[F]]] = M.fromEither {
    val servicesByRef = services.groupBy(_.ref).mapValues(_.head)

    val incomingEdges = services.foldMap { s =>
      Map(s.ref -> Set.empty[Service.Ref]) ++ s.dependencies.toList.foldMap { dep =>
        Map(dep -> Set(s.ref))
      }
    }

    def refNotFound(ref: Service.Ref): Throwable =
      new IllegalStateException(s"Cannot find ref ${ref.show}. This should not happen!")

    for {
      sortedRefs <- Dag(incomingEdges).topologicalSort.map(_.reverse)
      sortedSpecs <- sortedRefs.traverse[Either[Throwable, ?], Service.Spec[F]](ref =>
        servicesByRef.get(ref).toRight(refNotFound(ref)))

    } yield sortedSpecs
  }

  private def setUp(spec: Spec[F], registered: ServicesByRef[F])(implicit ec: ExecutionContext): F[Registered[F]] =
    for {

      depenendanciesEnv <- spec.dependencies.toList
        .foldMapM[F, Map[String, String]](ref => registered.envFor(ref))

      registered <- ctrl.start(spec.mergeToContainersEnv(depenendanciesEnv))
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
