package com.itv.servicebox.algebra

import cats.MonadError
import cats.data.NonEmptyList

abstract class Registry[F[_]](implicit M: MonadError[F, Throwable]) {
  def register(service: Service.Spec[F]): F[Service.Registered[F]]

  def updateStatus(id: Service.Ref, cId: Container.Ref, status: Status): F[Service.Registered[F]]

  def lookup(id: Service.Ref): F[Option[Service.Registered[F]]]

  def unsafeLookup(id: Service.Ref): F[Service.Registered[F]] =
    M.flatMap(lookup(id))(
      _.fold(M.raiseError[Service.Registered[F]](
        new IllegalArgumentException(s"Cannot lookup a service with id: ${id.value} in registry")))(M.pure))

  def lookupOrRegister(spec: Service.Spec[F]): F[Service.Registered[F]] =
    M.flatMap(lookup(spec.ref))(_.fold(register(spec))(M.pure))

  def deregister(id: Service.Ref): F[Unit]
}

object Registry {
  case class Location(host: String, port: Int)
  object Location {
    def localhost(port: Int): Location = Location("127.0.0.1", port)
  }
  type Endpoints = NonEmptyList[Location]

  case class EmptyPortList(containerRefs: List[Container.Ref]) extends Throwable
}
