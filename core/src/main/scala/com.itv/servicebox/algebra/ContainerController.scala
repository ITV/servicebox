package com.itv.servicebox.algebra

import cats.MonadError
import cats.data.NonEmptyList

abstract class ContainerController[F[_]](implicit M: MonadError[F, Throwable]) {
  def containersFor(spec: Service.Spec[F]): F[List[Container.Registered]]
  def startContainer(serviceSpec: Service.Spec[F], ref: Container.Ref): F[Unit]
  def stopContainer(sRef: Service.Ref, spec: Container.Registered): F[Unit]
}
object ContainerController {
  case class InvalidStateTransit(from: NonEmptyList[Status], to: Status) extends Throwable
}
