package com.itv.servicebox.algebra

import cats.{Applicative, MonadError}
import Status.Running
import cats.syntax.functor._
import cats.instances.list._
import cats.syntax.traverse._
import cats.syntax.flatMap._

abstract class ServiceController[F[_]](registry: Registry[F], containerCtrl: ContainerController[F])(
    implicit M: MonadError[F, Throwable],
    A: Applicative[F]) {

  def start(service: Service.Spec[F]): F[Service.Registered[F]] =
    for {
      running <- containerCtrl.containersFor(service).map(_.filter(_.status == Running).map(_.ref).toSet)
      containerIds = service.containers.map(c => c.ref(service.ref)).toList.toSet
      toRun        = containerIds.diff(running).toList
      _          <- toRun.traverse(containerCtrl.startContainer(service, _))
      registered <- registry.unsafeLookup(service.ref)

    } yield registered

  def stop(service: Service.Registered[F]): F[Service.Registered[F]] =
    for {
      containers <- containerCtrl.containersFor(service.toSpec)
      _          <- containers.traverse(containerCtrl.stopContainer(service.ref, _))
      registered <- registry.unsafeLookup(service.ref)
    } yield registered

  def waitUntilReady(service: Service.Registered[F]): F[Unit]
}
