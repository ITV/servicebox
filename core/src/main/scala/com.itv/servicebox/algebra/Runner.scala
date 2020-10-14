package com.itv.servicebox.algebra

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import cats.MonadError
import cats.syntax.all._
import cats.instances.all._
import Service._
import cats.effect.Effect

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

class Runner[F[_]](srvCtrl: ServiceController[F], networkCtrl: NetworkController[F], registry: ServiceRegistry[F])(
    serviceSeq: Spec[F]*)(implicit M: MonadError[F, Throwable], E: Effect[F], tag: AppTag) {

  private val services           = serviceSeq.toList
  private val tearedDownServices = new AtomicReference[Set[Service.Ref]](Set.empty[Service.Ref])

  def setUp(implicit ec: ExecutionContext): F[ServicesByRef[F]] =
    for {
      _          <- networkCtrl.createNetwork
      services   <- servicesInReverseTopologicalOrder
      registered <- services.foldM(ServicesByRef.empty[F])((acc, spec) => setUp(spec, acc).map(acc + _))
    } yield registered

  def setupWithRuntimeInfo(implicit ec: ExecutionContext): F[List[(Registered[F], RuntimeInfo)]] =
    for {
      _        <- networkCtrl.createNetwork
      services <- servicesInReverseTopologicalOrder
      registered <- services.foldM(List.empty[(Registered[F], RuntimeInfo)])((acc, s) =>
        setupWithRuntimeInfo(s).map(acc :+ _))
    } yield registered

  def tearDown(implicit ec: ExecutionContext): F[Unit] =
    services.foldM(())((_, s) => tearDownOnce(s).void)

  private def servicesInReverseTopologicalOrder: F[List[Service.Spec[F]]] = M.fromEither {
    val servicesByRef = services.groupBy(_.ref).view.mapValues(_.head)

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

      registered <- srvCtrl.start(spec.mergeToContainersEnv(depenendanciesEnv))
      _          <- srvCtrl.waitUntilReady(registered)
    } yield registered

  private def setupWithRuntimeInfo(spec: Spec[F])(implicit ec: ExecutionContext): F[(Registered[F], RuntimeInfo)] =
    for {
      t1         <- E.delay(System.currentTimeMillis())
      registered <- srvCtrl.start(spec)
      t2         <- E.delay(System.currentTimeMillis())
      _          <- srvCtrl.waitUntilReady(registered)
      t3         <- E.delay(System.currentTimeMillis())
    } yield {
      val setupTime      = FiniteDuration(t2 - t1, TimeUnit.MILLISECONDS)
      val readyCheckTime = FiniteDuration(t3 - t2, TimeUnit.MILLISECONDS)
      (registered, Service.RuntimeInfo(setupTime, readyCheckTime))
    }

  private def tearDownOnce(spec: Spec[F]): F[Unit] =
    for {
      alreadyDone     <- E.delay(tearedDownServices.get())
      maybeRegistered <- registry.lookup(spec)
      _ <- maybeRegistered.filterNot(srv => alreadyDone(srv.ref)).fold(M.unit) { srv =>
        srvCtrl.tearDown(srv) *> E.delay(tearedDownServices.getAndUpdate(_ + srv.ref))
      }
      _ <- E
        .delay(tearedDownServices.get)
        .map(_ == services.map(_.ref).toSet)
        .ifM(E.delay(Thread.sleep(2000)) *> networkCtrl.removeNetwork, E.unit)

    } yield ()
}
