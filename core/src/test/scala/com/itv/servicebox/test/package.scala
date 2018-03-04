package com.itv.servicebox

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.IO
import com.itv.servicebox.algebra._
import com.itv.servicebox.interpreter.{IOLogger, IORunner, IOServiceController, InMemoryServiceRegistry}
import org.scalatest.Assertion
import org.scalatest.Matchers._

import scala.concurrent.duration._

package object test {
  case class Modules[F[_]](runner: Runner[F], serviceRegistry: ServiceRegistry[F], imageRegistry: ImageRegistry[F])

  object TestData {

    val portRange = 49152 to 49162
    val appTag    = AppTag("org", "test")

    def constantReady[F[_]](implicit A: Applicative[F]) = Service.ReadyCheck[F](_ => A.pure(true), 3.millis)

    def postgresSpec[F[_]](implicit A: Applicative[F]) = Service.Spec[F](
      appTag,
      "db",
      NonEmptyList.of(Container.Spec("postgres:9.5.4", Map("POSTGRES_DB" -> appTag.appName), List(5432))),
      constantReady[F]
    )

    def rabbitSpec[F[_]](implicit A: Applicative[F]) = Service.Spec[F](
      appTag,
      "rabbit",
      NonEmptyList.of(Container.Spec("rabbitmq:3.6.10-management", Map.empty, List(5672, 15672))),
      Service.ReadyCheck[F](_ => A.pure(true), 3.millis)
    )
  }

  case class TestData[F[_]](portRange: Range, services: List[Service.Spec[F]])(implicit A: Applicative[F]) {
    def postgresSpec =
      services
        .find(_.name == TestData.postgresSpec[F].name)
        .getOrElse(fail(s"Undefined service spec ${TestData.postgresSpec.name}"))

    def rabbitSpec =
      services
        .find(_.name == TestData.rabbitSpec[F].name)
        .getOrElse(fail(s"Undefined service spec ${TestData.rabbitSpec.name}"))
  }

  case class Dependencies[F[_]](imageRegistry: ImageRegistry[F], containerController: ContainerController[F])
  object Dependencies {
    val inMemoryIO = {
      val logger        = IOLogger
      val imageRegistry = new fake.InMemoryImageRegistry(logger)
      val containerCtrl = new fake.ContainerController(imageRegistry, logger)
      Dependencies[IO](imageRegistry, containerCtrl)
    }
  }

  def withDependencies(dependencies: Dependencies[IO])(testData: TestData[IO])(
      f: (Runner[IO], ServiceRegistry[IO], Dependencies[IO]) => IO[Assertion]): Assertion = {
    val serviceRegistry = new InMemoryServiceRegistry(testData.portRange, IOLogger)
    val controller      = new IOServiceController(serviceRegistry, dependencies.containerController)
    val runner          = new IORunner(controller)
    f(runner, serviceRegistry, dependencies).unsafeRunSync()
  }
}
