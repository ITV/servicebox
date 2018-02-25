package com.itv.servicebox.algebra

import cats.MonadError

abstract class Controller[F[_]](implicit M: MonadError[F, Throwable]) {
  def isRunning(service: Service.Spec[F]): F[Boolean]
  def start(service: Service.Spec[F]): F[Unit]
  def stop(service: Service.Id): F[Unit]
  def pause(service: Service.Id): F[Unit]
  def unpause(service: Service.Id): F[Unit]
  def waitUntilReady(service: Service.Registered[F]): F[Unit]
}
object Controller {
  case class InvalidStateTransit(from: Service.Status, to: Service.Status) extends Throwable
}
