package com.itv.servicebox.algebra

import cats.MonadError
import cats.data.NonEmptyList
import cats.syntax.show._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.itv.servicebox.algebra.ContainerController.ContainerGroups

abstract class ContainerController[F[_]](imageRegistry: ImageRegistry[F], logger: Logger[F])(
    implicit M: MonadError[F, Throwable]) {
  def containerGroups(spec: Service.Registered[F]): F[ContainerGroups]

  def runningContainers(spec: Service.Registered[F]): F[List[Container.Registered]] =
    M.map(containerGroups(spec))(_.matched)

  protected def startContainer(tag: AppTag, container: Container.Registered): F[Unit]

  def fetchImageAndStartContainer(service: Service.Registered[F], ref: Container.Ref): F[Unit] =
    for {
      container <- service.containers
        .find(_.ref == ref)
        .fold(M.raiseError[Container.Registered](
          new IllegalArgumentException(s"Cannot find a container ref ${ref.show}")))(M.pure)
      _ <- imageRegistry.fetchUnlessExists(container.imageName)
      _ <- startContainer(service.tag, container)

    } yield ()

  def stopContainer(tag: AppTag, container: Container.Registered): F[Unit]
}
object ContainerController {
  case class ContainerGroups(matched: List[Container.Registered], notMatched: List[Container.Registered])
  object ContainerGroups {
    val Empty = ContainerGroups(Nil, Nil)
  }
  case class InvalidStateTransit(from: NonEmptyList[State], to: State) extends Throwable
}
