package com.itv.servicebox.algebra

import cats.MonadError
import cats.data.NonEmptyList

abstract class Controller[F[_]](registry: Registry[F])(implicit M: MonadError[F, Throwable]) {
  def containersFor(spec: Service.Spec[F]): F[List[Container.Registered]]
  def start(service: Service.Spec[F]): F[Service.Registered[F]]
  def stop(service: Service.Registered[F]): F[Service.Registered[F]]
  def waitUntilReady(service: Service.Registered[F]): F[Unit]
}
object Controller {
  case class InvalidStateTransit(from: NonEmptyList[Status], to: Status) extends Throwable
}
