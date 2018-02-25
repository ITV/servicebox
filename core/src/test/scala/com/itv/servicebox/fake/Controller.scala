package com.itv.servicebox.fake

import cats.effect.IO
import com.itv.servicebox.algebra
import cats.syntax.functor._
import cats.syntax.monadError._
import fs2.async.Ref
import algebra.Service
import Service.Status._

class Controller extends algebra.Controller[IO] {
  import algebra.Controller.InvalidStateTransit

  private val running = Ref[IO, Map[Service.Id, Service.Status]](Map.empty).unsafeRunSync()

  override def isRunning(service: Service.Spec[IO]): IO[Boolean] =
    for {
      servicesById <- running.get
    } yield servicesById.exists { case (id, status) => id == service.id && status == Running }

  override def start(service: Service.Spec[IO]): IO[Unit] =
    transitState(service.id, NotRunning, Running)

  override def stop(id: Service.Id): IO[Unit] =
    transitState(id, Running, NotRunning)

  override def pause(id: Service.Id): IO[Unit] =
    transitState(id, Running, Paused)

  override def unpause(id: Service.Id): IO[Unit] =
    transitState(id, Paused, Running)

  private def transitState(id: Service.Id, from: Service.Status, to: Service.Status): IO[Unit] = {
    val err = InvalidStateTransit(_: Service.Status, to)

    for {
      _            <- IO.pure(from).ensureOr(err)(_ != to)
      currentState <- running.get.map(_.get(id))
      _            <- currentState.fold(IO.pure(from))(s => IO.pure(s).ensureOr(err)(_ == from))
      _            <- running.modify(_.updated(id, to))
    } yield ()
  }

  override def waitUntilReady(service: Service.Registered[IO]): IO[Unit] =
    service.readyCheck.isReady(service.endpoints).void
}
