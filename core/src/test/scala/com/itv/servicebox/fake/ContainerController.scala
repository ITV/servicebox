package com.itv.servicebox.fake

import cats.data.NonEmptyList
import cats.effect.IO
import com.itv.servicebox.algebra
import com.itv.servicebox.algebra._
import cats.syntax.traverse._
import cats.instances.list._
import ContainerController.InvalidStateTransit
import com.itv.servicebox.algebra.Status._
import org.scalatest.Matchers._
import fs2.async.Ref

class ContainerController(strategy: CleanupStrategy,
                          initialState: Map[Container.Ref, Container.Registered],
                          registry: Registry[IO])
    extends algebra.ContainerController[IO] {

  private val containersByRef = Ref[IO, Map[Container.Ref, Container.Registered]](initialState).unsafeRunSync()

  override def containersFor(spec: Service.Spec[IO]): IO[List[Container.Registered]] =
    for {
      containers <- spec.containers.toList.traverse[IO, Option[Container.Registered]] { c =>
        containersByRef.get.map(_.get(c.ref(spec.ref)))
      }
    } yield containers.flatten

  override def startContainer(serviceSpec: Service.Spec[IO], ref: Container.Ref): IO[Unit] =
    for {
      service <- registry.lookupOrRegister(serviceSpec)
      container <- service.containers
        .find(_.ref == ref)
        .fold(IO.raiseError[Container.Registered](
          new IllegalArgumentException(s"Cannot find container with id ${ref.value} in service: $service")))(IO.pure)

      _ <- containersByRef.modify(_.updated(ref, container))
      _ <- strategy match {
        case CleanupStrategy.Pause =>
          changeContainerState(container.ref, NonEmptyList.of(Status.Paused, Status.NotRunning), Status.Running)
        case CleanupStrategy.Destroy =>
          changeContainerState(container.ref, NonEmptyList.of(Status.NotRunning, Status.Paused), Status.Running)
      }
      _ <- registry.updateStatus(serviceSpec.ref, ref, Status.Running)

    } yield ()

  override def stopContainer(sRef: Service.Ref, spec: Container.Registered) = {
    import cats.syntax.functor._
    val changeState: IO[Status] = strategy match {
      case CleanupStrategy.Pause => changeContainerState(spec.ref, NonEmptyList.of(Running), Paused).as(Paused)
      case CleanupStrategy.Destroy =>
        changeContainerState(spec.ref, NonEmptyList.of(Running), NotRunning).as(NotRunning)
    }
    changeState.flatMap(newState => registry.updateStatus(sRef, spec.ref, newState).void)
  }

  private def changeContainerState(ref: Container.Ref, from: NonEmptyList[Status], to: Status): IO[Unit] = {
    import cats.syntax.monadError._
    val err = InvalidStateTransit(_: NonEmptyList[Status], to)

    for {
      currentState <- containersByRef.get.map(_.get(ref).map(_.status))
      _ <- currentState.fold(IO.pure(from))(s =>
        IO.pure(NonEmptyList.of(s)).ensureOr(err)(s => from.toList.contains(s.head)))
      _ <- containersByRef.modify { containers =>
        containers
          .get(ref)
          .map { c =>
            containers.updated(ref, c.copy(status = to))
          }
          .getOrElse(fail(s"Failed to resolve container ${ref.value}. This shouldn't happen"))
      }
    } yield ()
  }
}
