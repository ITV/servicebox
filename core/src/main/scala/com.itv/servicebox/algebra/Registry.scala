package com.itv.servicebox.algebra

import cats.MonadError
import cats.data.NonEmptyList

abstract class Registry[F[_]](implicit M: MonadError[F, Throwable]) {
  def register(service: Service.Spec[F]): F[Service.Registered[F]]
  def updateStatus(id: Service.Id, status: Service.Status): F[Unit]
  def lookup(id: Service.Id): F[Option[Service.Registered[F]]]
  def deregister(id: Service.Id): F[Unit]
}

object Registry {
  case class Location(host: String, port: Int)
  type Endpoints = NonEmptyList[Location]
}
