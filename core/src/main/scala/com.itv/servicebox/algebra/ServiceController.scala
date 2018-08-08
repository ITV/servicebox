package com.itv.servicebox.algebra

import cats.MonadError
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.concurrent.ExecutionContext

class ServiceController[F[_]](logger: Logger[F],
                              registry: ServiceRegistry[F],
                              ctrl: ContainerController[F],
                              scheduler: Scheduler[F])(implicit M: MonadError[F, Throwable], tag: AppTag) {

  def start(spec: Service.Spec[F]): F[Service.Registered[F]] =
    for {
      _               <- logger.info(s"looking up service ${spec.ref.show}")
      registered      <- registry.lookupOrRegister(spec)
      containerGroups <- ctrl.containerGroups(registered)

      _ <- logger.debug(
        s"found ${containerGroups.notMatched.size} running containers which do not match the current spec for ${spec.ref}: ${containerGroups.notMatched} ...")

      toStop = containerGroups.notMatched
      _ <- toStop.traverse(c => ctrl.removeContainer(registered.ref, c.ref))

      toStart = registered.containers.filterNot(c => containerGroups.matched.exists(_.ref == c.ref))
      _ <- logger.debug(
        s"found ${containerGroups.matched.size} running containers matching the current spec for ${spec.ref}. Starting ${toStart.size} ...")

      _ <- containerGroups.matched.traverse(c => registry.updatePortMappings(spec.ref, c.ref, c.portMappings))
      _ <- toStart.traverse(startAndUpdateRegistry(registered, _))

      registered <- registry.unsafeLookup(spec)

    } yield registered

  private def startAndUpdateRegistry(service: Service.Registered[F], container: Container.Registered): F[Unit] =
    for {
      container <- service.containers
        .find(_.ref == container.ref)
        .fold(M.raiseError[Container.Registered](
          new IllegalArgumentException(s"Cannot find a container ref ${container.ref.show}")))(M.pure)
      _ <- ctrl.fetchImageAndStartContainer(service.ref, container)
    } yield ()

  def tearDown(service: Service.Registered[F]): F[Unit] = {
    def rmUpdatingRegistry(serviceRef: Service.Ref, container: Container.Registered) =
      ctrl.removeContainer(serviceRef, container.ref) >> registry.deregister(serviceRef, container.ref).void
    for {
      containers <- ctrl.runningContainers(service)
      _          <- logger.info(s"found ${containers.size} containers to delete ...")
      _          <- containers.traverse(rmUpdatingRegistry(service.ref, _))
    } yield ()
  }

  def waitUntilReady(service: Service.Registered[F])(implicit ec: ExecutionContext): F[Unit] = {
    def check = () => service.readyCheck.isReady(service.endpoints)
    val label = service.readyCheck.label.getOrElse(service.ref.show)
    scheduler.retry(check, service.readyCheck.attemptTimeout, service.readyCheck.totalTimeout, label)
  }
}
