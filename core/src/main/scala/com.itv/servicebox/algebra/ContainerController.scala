package com.itv.servicebox.algebra

import cats.MonadError
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.itv.servicebox.algebra.ContainerController.ContainerGroups
import Container._

abstract class ContainerController[F[_]](imageRegistry: ImageRegistry[F],
                                         logger: Logger[F],
                                         network: Option[NetworkName])(implicit M: MonadError[F, Throwable]) {
  def containerGroups(spec: Service.Registered[F]): F[ContainerGroups]

  //TODO: revisit this, as it hides parsing errors
  def runningContainers(spec: Service.Registered[F]): F[List[Registered]] =
    containerGroups(spec).map(_.matched)
//    containerGroups(spec).flatMap { groups =>
//      if (groups.notMatched.nonEmpty)
//        M.raiseError(
//          new IllegalStateException(
//            s"Some containers are mismatched:\n expected: ${spec.toSpec}\n actual: ${groups.notMatched.head.toSpec}"))
//      else
//        M.pure(groups.matched)
//    }

  protected def startContainer(serviceRef: Service.Ref, container: Registered): F[Unit]

  def fetchImageAndStartContainer(serviceRef: Service.Ref, container: Registered): F[Unit] =
    imageRegistry.fetchUnlessExists(container.imageName) >> startContainer(serviceRef, container)

  def removeContainer(serviceRef: Service.Ref, container: Ref): F[Unit]
}
object ContainerController {
  case class ContainerGroups(matched: List[Registered], notMatched: List[(Registered, Diff)])
  object ContainerGroups {
    val Empty = ContainerGroups(Nil, Nil)
  }
}
