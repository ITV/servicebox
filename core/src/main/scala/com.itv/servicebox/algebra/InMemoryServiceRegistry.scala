package com.itv.servicebox.algebra

import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

import cats.{Applicative, MonadError}
import cats.data.{NonEmptyList, StateT}
import cats.instances.list._
import cats.instances.map._
import cats.instances.set._
import cats.instances.stream._
import cats.syntax.all._

import scala.util.Try

object InMemoryServiceRegistry {
  val DefaultPortRange = 49162 to 49262
}

class InMemoryServiceRegistry[F[_]](range: Range, logger: Logger[F])(implicit tag: AppTag,
                                                                     I: ImpureEffect[F],
                                                                     M: MonadError[F, Throwable])
    extends ServiceRegistry[F](logger) {

  private val registry  = new AtomicReference[Map[Service.Ref, ContainerMappings]](Map.empty)
  private val portRange = new AtomicReference[Range](range)

  private val getRange: PortAllocation[F, Range] =
    StateT.get[F, AtomicReference[Range]].map(_.get())

  private def dropFromRange(n: Int)(implicit A: Applicative[F]): PortAllocation[F, Unit] =
    StateT.modifyF[F, AtomicReference[Range]] { ref =>
      I.lift(ref.getAndUpdate(_.drop(n))) *> A.pure(ref)
    }

  private def lift[F[_]: Applicative, A](fa: F[A]): PortAllocation[F, A] =
    StateT.liftF[F, AtomicReference[Range], A](fa)

  private def allocate(container: Container.Spec, serviceRef: Service.Ref)(
      implicit A: Applicative[F]): PortAllocation[F, Container.Registered] =
    for {
      range <- getRange
      _     <- lift(logger.debug(s"current port range: $range"))
      attemptedPorts <- lift(range.toStream.foldM(List.empty[Option[Int]]) {
        case (acc, port) =>
          if (acc.flatten.size == container.internalPorts.size)
            A.pure(acc)
          else
            checkPort(port).map(isAvailable => acc :+ Some(port).filter(_ => isAvailable))
      })

      _          <- lift(logger.debug(s"attempted ports: $attemptedPorts"))
      registered <- lift(M.fromEither(container.register(attemptedPorts.flatten, serviceRef)))
      _          <- dropFromRange(attemptedPorts.size)
    } yield registered

  private def checkPort(port: Int): F[Boolean] = I.lift {
    Try {
      new Socket("localhost", port).close()
    }.toOption.isEmpty
  }

  override def register(service: Service.Spec[F]) =
    for {
      registeredContainers <- service.containers.toList
        .traverse[PortAllocation[F, ?], Container.Registered](c => allocate(c, service.ref))
        .runA(portRange)

      err = ServiceRegistry.EmptyPortList(registeredContainers.map(_.ref))

      locations <- NonEmptyList
        .fromList(registeredContainers.flatMap(_.portMappings))
        .fold(M.raiseError[NonEmptyList[Location]](err)) { ports =>
          M.pure(ports.map((Location.localhost _).tupled))
        }

      rs = Service.Registered(
        service.name,
        NonEmptyList.fromListUnsafe(registeredContainers),
        Endpoints(locations),
        service.readyCheck,
        service.dependencies
      )
      summary = registeredContainers
        .map(c => s"[${c.ref.show}] ${c.portMappings.map { case (host, guest) => s"$host -> $guest" }.mkString(", ")}")
        .mkString("\n")

      portMappings = rs.containers.foldMap(c => Map(c.ref -> c.portMappings))

      _ <- logger.debug(s"registering containers with port ranges:\n\t$summary")
      _ <- I.lift(registry.getAndUpdate(_.updated(rs.ref, portMappings)))

    } yield rs

  override def deregister(id: Service.Ref) =
    I.lift(registry.getAndUpdate(_ - id)).void

  override def deregister(id: Service.Ref, cRef: Container.Ref) =
    I.lift(registry.getAndUpdate { m =>
        m.get(id)
          .map { mappings =>
            m.updated(id, mappings - cRef)
          }
          .getOrElse(m)
      })
      .void

  override def lookup(id: Service.Ref) =
    I.lift(registry.get).map(_.get(id).filter(_.nonEmpty))

  override def updatePortMappings(id: Service.Ref, cId: Container.Ref, mappings: Set[PortMapping]) =
    I.lift(registry.getAndUpdate { data =>
        val updated = data
          .get(id)
          .map { m =>
            m.updated(cId, mappings)
          }
          .fold(data)(updatedSrv => data.updated(id, updatedSrv))

        logger.debug(s"updated port mappings: ${updated} | mappings set: ${mappings}")

        updated
      })
      .void

}
