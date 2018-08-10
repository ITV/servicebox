package com.itv.servicebox.fake

import java.util.concurrent.atomic.AtomicReference

import cats.instances.list._
import cats.syntax.show._
import cats.syntax.traverse._
import com.itv.servicebox.algebra
import com.itv.servicebox.algebra.ContainerController.ContainerGroups
import com.itv.servicebox.algebra._
import org.scalatest.Matchers._
import ContainerController.{ContainerStates, ContainerWithState}
import cats.MonadError
import cats.data.NonEmptyList
import cats.effect.Effect
import cats.syntax.flatMap._
import cats.syntax.functor._

class ContainerController[F[_]](
    imageRegistry: ImageRegistry[F],
    logger: Logger[F],
    network: Option[NetworkName],
    initialState: ContainerStates = Map.empty)(implicit tag: AppTag, E: Effect[F], M: MonadError[F, Throwable])
    extends algebra.ContainerController[F](imageRegistry, logger, network) {

  private val containersByRef = new AtomicReference[ContainerStates](initialState)

  def containerGroups(spec: Service.Registered[F]) = {
    import PortSpec.onlyInternalEq
    import Container.Diff
    import Diff.Entry

    for {
      containers <- spec.containers.toList
        .traverse[F, Option[ContainerWithState]] { c =>
          val ref = c.ref(spec.ref)
          E.delay(containersByRef.get).map(_.get(ref).filter(_.container.toSpec === c.toSpec))
        }
        .map(_.flatten)
    } yield {
      val (running, notRunning) = containers.partition(_.isRunning)

      ContainerGroups(running.map(_.container),
                      notRunning.map(_.container -> Diff(NonEmptyList.of(Entry("diff-suppressed", "...")))))
    }
  }

  override protected def startContainer(serviceRef: Service.Ref, container: Container.Registered): F[Unit] =
    for {
      _ <- logger.info(
        s"starting container ${container.ref.show} service ref: ${serviceRef.show} with port mappings: ${container.portMappings
          .map { case (host, guest) => s"$host -> $guest" }
          .mkString(", ")}")
      _ <- E.delay(
        containersByRef.getAndUpdate(_.updated(container.ref, ContainerWithState(container, isRunning = true))))
    } yield ()

  override def removeContainer(serviceRef: Service.Ref, containerRef: Container.Ref) =
    for {
      _ <- logger.info(s"stopping container ${containerRef.show} with service ref: ${serviceRef.show}")
      _ <- shutdownContainer(containerRef)
    } yield ()

  private def shutdownContainer(ref: Container.Ref): F[Unit] =
    for {
      _ <- E.delay(containersByRef.getAndUpdate { containers =>
        containers
          .get(ref)
          .map { c =>
            containers.updated(ref, c.copy(isRunning = false))
          }
          .getOrElse(fail(s"Failed to resolve container ${ref.value}. This shouldn't happen"))
      })
    } yield ()
}
object ContainerController {
  case class ContainerWithState(container: Container.Registered, isRunning: Boolean)
  type ContainerStates = Map[Container.Ref, ContainerWithState]
}
