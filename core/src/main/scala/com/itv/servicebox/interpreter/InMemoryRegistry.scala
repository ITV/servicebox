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
  private val registry  = Ref[IO, Map[Service.Ref, Service.Registered[IO]]](Map.empty).unsafeRunSync()
  private val portRange = Ref[IO, Range](range).unsafeRunSync()

  private def allocatePorts(containerPorts: List[Int]): IO[List[Container.PortMapping]] =
    for {
      range <- portRange.get
      mappings <- if (range.size > containerPorts.size)
        IO.pure(containerPorts.zip(range.take(containerPorts.size)).map(_.swap))
      else
        IO.raiseError(new IllegalStateException(s"cannot allocate ${containerPorts.size} port/s within range: $range"))
      _ <- portRange.modify(_.drop(containerPorts.size))

    } yield mappings

  override def register(service: Service.Spec[IO]) =
    for {
      registeredContainers <- service.containers.toList.traverse[IO, Container.Registered](c =>
        allocatePorts(c.internalPorts).map { portMapping =>
          val id = Container.Ref(s"${service.ref.value}/${c.imageName}")
          Container.Registered(id, c.imageName, c.env, portMapping, Status.NotRunning)
      })

      endpoints = NonEmptyList
        .fromListUnsafe {
          registeredContainers.flatMap(_.portMappings.map(_._1))
        }
        .map(Location("127.0.0.1", _))

      rs = Service.Registered(
        service.tag,
        service.name,
        NonEmptyList.fromListUnsafe(registeredContainers),
        endpoints,
        service.readyCheck
      )

      _ <- registry.modify(_.updated(rs.ref, rs))

    } yield rs

  override def deregister(id: Service.Ref) =
    registry.modify(_ - id).void

  override def lookup(id: Service.Ref) =
    registry.get.map(_.get(id))

  override def updateStatus(id: Service.Ref, cId: Container.Ref, status: Status) = {
    def update(r: Map[Service.Ref, Service.Registered[IO]]) =
      for {
        service          <- r.get(id)
        (container, idx) <- service.containers.zipWithIndex.find(_._1.ref == cId)
        updatedContainers = NonEmptyList.fromListUnsafe(
          service.containers.toList.updated(idx, container.copy(status = status)))

      } yield r.updated(id, service.copy(containers = updatedContainers))

    for {

      registered <- registry
        .modify { r =>
          update(r).getOrElse(r)
        }
        .map(_.now(id))

    } yield registered
  }
}
