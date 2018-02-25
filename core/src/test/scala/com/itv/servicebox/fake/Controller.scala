package com.itv.servicebox.fake

import cats.effect.IO
import com.itv.servicebox.algebra
import fs2.async.Ref
import algebra._
import cats.instances.list._
import cats.syntax.traverse._
import cats.syntax.functor._
import Status._
import cats.data.NonEmptyList
import com.itv.servicebox.algebra.Controller.InvalidStateTransit

class Controller(strategy: CleanupStrategy,
                 registry: Registry[IO],
                 containerState: Map[Container.Ref, Container.Registered] = Map.empty)
    extends algebra.Controller[IO](registry) {

  private val containersByRef = Ref[IO, Map[Container.Ref, Container.Registered]](containerState).unsafeRunSync()

  override def containersFor(spec: Service.Spec[IO]) =
    for {
      containers <- spec.containers.toList.traverse[IO, Option[Container.Registered]] { c =>
        containersByRef.get.map(_.get(c.ref(spec.ref)))
      }
    } yield containers.flatten

  override def start(service: Service.Spec[IO]) =
    for {
      running <- containersFor(service).map(_.filter(_.status == Running).map(_.ref).toSet)
      containerIds = service.containers.map(c => c.ref(service.ref)).toList.toSet
      toRun        = containerIds.diff(running).toList
      _          <- toRun.traverse(startContainer(service, _))
      registered <- registry.unsafeLookup(service.ref)

    } yield registered

  private def startContainer(serviceSpec: Service.Spec[IO], ref: Container.Ref): IO[Unit] =
    for {
      service <- registry.lookupOrRegister(serviceSpec)
      container <- service.containers
        .find(_.ref == ref)
        .fold(IO.raiseError[Container.Registered](
          new IllegalArgumentException(s"Cannot find container with id ${ref.value} in service: $service")))(IO.pure)

      _ <- containersByRef.modify(_.updated(ref, container))
      _ <- strategy match {
        case CleanupStrategy.Pause =>
          transitState(container.ref, NonEmptyList.of(Status.Paused, Status.NotRunning), Status.Running)
        case CleanupStrategy.Destroy =>
          transitState(container.ref, NonEmptyList.of(Status.NotRunning, Status.Paused), Status.Running)
      }
      _ <- registry.updateStatus(serviceSpec.ref, ref, Status.Running)

    } yield ()

  override def stop(service: Service.Registered[IO]) =
    for {
      containers <- containersFor(service.toSpec)
      _          <- containers.traverse(stopContainer(service.ref, _))
      registered <- registry.unsafeLookup(service.ref)
    } yield registered

  private def stopContainer(sId: Service.Ref, spec: Container.Registered) = {
    val changeState: IO[Status] = strategy match {
      case CleanupStrategy.Pause   => transitState(spec.ref, NonEmptyList.of(Running), Paused).as(Paused)
      case CleanupStrategy.Destroy => transitState(spec.ref, NonEmptyList.of(Running), NotRunning).as(NotRunning)
    }
    changeState.flatMap(newState => registry.updateStatus(sId, spec.ref, newState))
  }

  private def transitState(id: Container.Ref, from: NonEmptyList[Status], to: Status): IO[Unit] = {
    import cats.syntax.monadError._
    val err = InvalidStateTransit(_: NonEmptyList[Status], to)

    for {
      currentState <- containersByRef.get.map(_.get(id).map(_.status))
      _ = println(s"transition: ${currentState} -  from: $from, to: $to")
      _ <- currentState.fold(IO.pure(from))(s =>
        IO.pure(NonEmptyList.of(s)).ensureOr(err)(s => from.toList.contains(s.head)))
      _ <- containersByRef.modify { containers =>
        containers
          .get(id)
          .map { c =>
            containers.updated(id, c.copy(status = to))
          }
          .getOrElse(sys.error("Something went badly!"))
      }
    } yield ()
  }

  override def waitUntilReady(service: Service.Registered[IO]): IO[Unit] =
    service.readyCheck.isReady(service.endpoints).void
}
