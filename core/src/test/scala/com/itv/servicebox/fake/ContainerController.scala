package com.itv.servicebox.fake

import cats.data.NonEmptyList
import cats.effect.IO
import com.itv.servicebox.algebra
import com.itv.servicebox.algebra._
import cats.syntax.traverse._
import cats.syntax.show._
import cats.instances.list._
import ContainerController.{ContainerGroups, InvalidStateTransit}
import com.itv.servicebox.algebra.State._
import org.scalatest.Matchers._
import fs2.async.Ref

class ContainerController(imageRegistry: ImageRegistry[IO],
                          initialState: Map[Container.Ref, Container.Registered],
                          logger: Logger[IO])
    extends algebra.ContainerController[IO](imageRegistry, logger) {

  private val containersByRef = Ref[IO, Map[Container.Ref, Container.Registered]](initialState).unsafeRunSync()

  def containerGroups(spec: Service.Registered[IO]) =
    for {
      containers <- spec.containers.toList
        .traverse[IO, Option[Container.Registered]] { c =>
          containersByRef.get.map(_.get(c.ref(spec.ref)))
        }
        .map(_.flatten)
    } yield (ContainerGroups.apply _).tupled(containers.partition(_.state == State.Running))

  override protected def startContainer(tag: AppTag, container: Container.Registered): IO[Unit] =
    for {
      _ <- logger.info(s"starting container ${container.ref.show} with app tag: ${tag.show}")
      _ <- containersByRef.modify(_.updated(container.ref, container))
      _ <- changeContainerState(container.ref, Running)
    } yield ()

  override def stopContainer(tag: AppTag, container: Container.Registered) =
    for {
      _ <- logger.info(s"stopping container ${container.ref.show} with app tag: ${tag.show}")
      _ <- changeContainerState(container.ref, NotRunning)
    } yield ()

  private def changeContainerState(ref: Container.Ref, state: State): IO[Unit] =
    for {
      _ <- containersByRef.modify { containers =>
        containers
          .get(ref)
          .map { c =>
            containers.updated(ref, c.copy(state = state))
          }
          .getOrElse(fail(s"Failed to resolve container ${ref.value}. This shouldn't happen"))
      }
    } yield ()
}
