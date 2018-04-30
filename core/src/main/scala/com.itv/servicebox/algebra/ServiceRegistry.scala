package com.itv.servicebox.algebra

import cats.MonadError
import cats.data.NonEmptyList
import com.itv.servicebox.algebra.Container.PortMapping
import com.itv.servicebox.algebra.ServiceRegistry.ContainerMappings
import cats.syntax.all._
import cats.instances.map._
import cats.instances.int._

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
  case class Location(host: String, port: Int, containerPort: Int)
  object Location {
    def localhost(port: Int, containerPort: Int): Location = Location("127.0.0.1", port, containerPort)
  }

  case class Endpoints(toNel: NonEmptyList[Location]) {
    val toList   = toNel.toList
    val mappings = toNel.foldMap(l => Map(l.port -> l.containerPort))

    def locationFor(containerPort: Int): Either[IllegalArgumentException, Location] =
      toNel
        .find(_.containerPort == containerPort)
        .toRight(
          new IllegalArgumentException(s"Cannot find a location for $containerPort. Existing port mappings: $mappings"))

    def unsafeLocationFor(containerPort: Int): Location =
      locationFor(containerPort).valueOr(throw _)
  }

  case class EmptyPortList(containerRefs: List[Container.Ref]) extends Throwable
  type ContainerMappings = Map[Container.Ref, Set[PortMapping]]
}
