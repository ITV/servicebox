package com.itv.servicebox.interpreter

import cats.data.NonEmptyList
import cats.effect.IO
import cats.instances.list._
import cats.syntax.traverse._
import com.itv.servicebox.algebra.Registry.Location
import com.itv.servicebox.algebra.{Registry, Service, _}
import fs2.async.Ref
import cats.syntax.functor._

class InMemoryRegistry(range: Range) extends Registry[IO] {
  private val registry  = Ref[IO, Map[Service.Id, Service.Registered[IO]]](Map.empty).unsafeRunSync()
  private val portRange = Ref[IO, Range](range).unsafeRunSync()

  private def allocatePorts(containerPorts: List[Int]): IO[List[Container.PortMapping]] =
    for {
      range <- portRange.get
      mappings <- if (range.size > containerPorts.size)
        IO.pure(containerPorts.zip(range.take(containerPorts.size)))
      else IO.raiseError(new IllegalStateException(s"cannot allocate ${containerPorts.size} within range: $range"))
      _ <- portRange.modify(_.drop(containerPorts.size))

    } yield mappings

  override def register(service: Service.Spec[IO]) =
    for {
      registeredContainers <- service.containers.toList.traverse[IO, Container.Registered](c =>
        allocatePorts(c.internalPorts).map { portMapping =>
          val id = Container.Id(s"${service.id}/${c.imageName}")
          Container.Registered(id, c.imageName, c.env, portMapping)
      })

      endpoints = NonEmptyList
        .fromListUnsafe {
          registeredContainers.flatMap(_.portMappings.map(_._2))
        }
        .map(Location("127.0.0.1", _))

      rs = Service.Registered(
        service.tag,
        service.name,
        NonEmptyList.fromListUnsafe(registeredContainers),
        endpoints,
        Service.Status.NotRunning,
        service.readyCheck
      )

      _ <- registry.modify(_.updated(rs.id, rs))

    } yield rs

  override def deregister(id: Service.Id) =
    registry.modify(_ - id).void

  override def lookup(id: Service.Id) =
    registry.get.map(_.get(id))

  override def updateStatus(id: Service.Id, status: Service.Status): IO[Unit] =
    registry.modify { r =>
      val maybeService = r.get(id) //TODO: validate status here
      maybeService.fold(r)(s => r.updated(id, s.copy(status = status)))
    }.void
}
