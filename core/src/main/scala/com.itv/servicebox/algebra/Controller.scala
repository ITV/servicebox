package com.itv.servicebox.algebra

import cats.MonadError

abstract class Controller[F[_]](registry: Registry[F])(implicit M: MonadError[F, Throwable]) {
  def start(service: Service.Spec[F]): F[Service.Registered[F]]
  def stop(service: Service.Id): F[Unit]
  def pause(service: Service.Id): F[Unit]
  def unpause(service: Service.Id): F[Unit]
  def waitUntilReady(service: Service.Registered[F]): F[Unit]
}
object Controller {
  case class InvalidStateTransit(from: Status, to: Status) extends Throwable
}
