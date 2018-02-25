package com.itv.servicebox.fake

import cats.effect.IO
import com.itv.servicebox.algebra
import cats.syntax.functor._
import fs2.async.Ref
import algebra._
import cats.instances.list._
import cats.syntax.traverse._
import Status._

class Controller(strategy: CleanupStrategy,
                 registry: Registry[IO],
                 containerState: Map[Container.Id, Container.Registered] = Map.empty)
    extends algebra.Controller[IO](registry) {

  private val running = Ref[IO, Map[Container.Id, Container.Registered]](containerState).unsafeRunSync()

  override def start(service: Service.Spec[IO]) =
    for {
      allContainers <- running.get
      containerIds = service.containers.map(c => c.id(service) -> c).toList.toSet
      running = allContainers.collect {
        case (id, c) if containerIds.exists(_._1 == id) && c.status == Running => id -> c.toSpec
      }.toSet
      toRun = containerIds.diff(running).map(_._1).toList
      _          <- toRun.traverse(startContainer(service, _))
      registered <- registry.unsafeLookup(service.id)

    } yield registered

  private def startContainer(serviceSpec: Service.Spec[IO], cId: Container.Id): IO[Unit] =
    for {
      service <- registry.lookupOrRegister(serviceSpec)
      container <- service.containers
        .find(_.id == cId)
        .fold(IO.raiseError[Container.Registered](
          new IllegalArgumentException(s"Cannot find container with id ${cId.value} in service: $service")))(IO.pure)
      _ <- running.modify(m => m.updated(cId, container))
      _ <- registry.updateStatus(serviceSpec.id, cId, Status.Running)

    } yield ()

  override def stop(id: Service.Id): IO[Unit] =
    transitState(id, Running, NotRunning)

  override def pause(id: Service.Id): IO[Unit] =
    transitState(id, Running, Paused)

  override def unpause(id: Service.Id): IO[Unit] =
    transitState(id, Paused, Running)

  private def transitState(id: Service.Id, from: Status, to: Status): IO[Unit] = ???
//    val err = InvalidStateTransit(_: Service.Status, to)
//
//    for {
//      _            <- IO.pure(from).ensureOr(err)(_ != to)
//      currentState <- running.get.map(_.get(id))
//      _            <- currentState.fold(IO.pure(from))(s => IO.pure(s).ensureOr(err)(_ == from))
//      _            <- running.modify(_.updated(id, to))
//    } yield ()
//  }

  override def waitUntilReady(service: Service.Registered[IO]): IO[Unit] =
    service.readyCheck.isReady(service.endpoints).void
}
