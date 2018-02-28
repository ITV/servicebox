package com.itv.servicebox.algebra

import cats.MonadError
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import com.itv.servicebox.algebra.Status.Running

abstract class ServiceController[F[_]](registry: Registry[F], ctrl: ContainerController[F])(
    implicit M: MonadError[F, Throwable]) {

  def start(service: Service.Spec[F]): F[Service.Registered[F]] =
    for {
      running <- ctrl.containersFor(service).map(_.filter(_.status == Running).map(_.ref).toSet)
      containerIds = service.containers.map(c => c.ref(service.ref)).toList.toSet
      toRun        = containerIds.diff(running).toList
      _          <- toRun.traverse(ctrl.startContainer(service, _))
      registered <- registry.unsafeLookup(service.ref)

    } yield registered

  def stop(service: Service.Registered[F]): F[Service.Registered[F]] =
    for {
      containers <- ctrl.containersFor(service.toSpec)
      _          <- containers.traverse(ctrl.stopContainer(service.ref, _))
      registered <- registry.unsafeLookup(service.ref)
    } yield registered

  def waitUntilReady(service: Service.Registered[F]): F[Unit]
}
