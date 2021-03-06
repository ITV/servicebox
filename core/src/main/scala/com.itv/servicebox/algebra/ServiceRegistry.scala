package com.itv.servicebox.algebra

import cats.MonadError
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.flatMap._
import cats.syntax.functor._

abstract class ServiceRegistry[F[_]](logger: Logger[F])(implicit M: MonadError[F, Throwable], appTag: AppTag) {
  def register(service: Service.Spec[F]): F[Service.Registered[F]]

  def updatePortMappings(id: Service.Ref, cId: Container.Ref, mapping: Set[PortMapping]): F[Unit]

  def lookup(id: Service.Ref): F[Option[ContainerMappings]]

  def lookup(spec: Service.Spec[F]): F[Option[Service.Registered[F]]] =
    for {
      maybeMappings <- lookup(spec.ref)
      maybeService <- maybeMappings.fold(M.pure(none[Service.Registered[F]])) { mappings =>
        addMappings(spec)(mappings).map(_.some)
      }

    } yield maybeService

  def unsafeLookup(spec: Service.Spec[F]): F[Service.Registered[F]] =
    M.flatMap(lookup(spec.ref))(
      _.fold(M.raiseError[Service.Registered[F]](new IllegalArgumentException(
        s"Cannot lookup a service with id: ${spec.ref.show} in registry")))(addMappings(spec)))

  def lookupOrRegister(spec: Service.Spec[F]): F[Service.Registered[F]] =
    M.flatMap(lookup(spec.ref))(_.fold(register(spec))(addMappings(spec)))

  def deregister(id: Service.Ref): F[Unit]

  def deregister(id: Service.Ref, cRef: Container.Ref): F[Unit]

  private def addMappings(spec: Service.Spec[F])(mappings: ContainerMappings): F[Service.Registered[F]] =
    spec.register(mappings).fold(err => M.raiseError(err), M.pure)
}

object ServiceRegistry {
  case class EmptyPortList(containerRefs: List[Container.Ref]) extends Throwable
}
