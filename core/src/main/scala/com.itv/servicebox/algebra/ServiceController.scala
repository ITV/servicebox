package com.itv.servicebox.algebra

import cats.Monad
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.syntax.traverse._

abstract class ServiceController[F[_]](logger: Logger[F], registry: ServiceRegistry[F], ctrl: ContainerController[F])(
    implicit M: Monad[F]) {

  def start(spec: Service.Spec[F]): F[Service.Registered[F]] =
    for {
      _               <- logger.info(s"looking up service ${spec.ref.show}")
      registered      <- registry.lookupOrRegister(spec)
      containerGroups <- ctrl.containerGroups(registered)

      _ <- logger.debug(
        s"found ${containerGroups.notMatched.size} running containers which do not match the current spec ...")

      toStop = containerGroups.notMatched
      _ <- toStop.traverse(c => stopAndUpdateRegistry(registered, c))

      toStart = registered.containers.map(_.running).filterNot(containerGroups.matched.contains)
      _ <- logger.debug(
        s"found ${containerGroups.matched.size} running containers matching the current spec. Starting ${toStart.size} ...")
      _ <- toStart.traverse(startAndUpdateRegistry(registered, _))

      registered <- registry.unsafeLookup(spec.ref)

    } yield registered

  private def startAndUpdateRegistry(service: Service.Registered[F], container: Container.Registered): F[Unit] =
    (ctrl.fetchImageAndStartContainer(service, container.ref) >>
      registry.updateStatus(service.ref, container.ref, State.Running)).void

  private def stopAndUpdateRegistry(service: Service.Registered[F], container: Container.Registered): F[Unit] =
    registry.updateStatus(service.ref, container.ref, State.NotRunning).void

  def stop(service: Service.Registered[F]): F[Service.Registered[F]] =
    for {
      containers <- ctrl.runningContainers(service)
      _          <- containers.traverse(stopAndUpdateRegistry(service, _))
      registered <- registry.unsafeLookup(service.ref)
    } yield registered

  def waitUntilReady(service: Service.Registered[F]): F[Unit]
}
