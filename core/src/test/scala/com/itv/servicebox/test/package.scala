package com.itv.servicebox

import cats.data.NonEmptyList
import cats.{Applicative, MonadError}
import com.itv.servicebox.algebra.{ImpureEffect, InMemoryServiceRegistry, _}
import org.scalatest.Assertion
import org.scalatest.Matchers._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

package object test {
  object TestData {
    val portRange       = 49152 to 49162
    implicit val appTag = AppTag("org", "test")

    def constantReady[F[_]](label: String)(implicit A: Applicative[F]): Service.ReadyCheck[F] =
      Service.ReadyCheck[F](_ => A.unit, 1.millis, 3.millis)

    def postgresSpec[F[_]: Applicative] = Service.Spec[F](
      "db",
      NonEmptyList.of(Container.Spec("postgres:9.5.4", Map("POSTGRES_DB" -> appTag.appName), Set(5432))),
      constantReady[F]("postgres ready check")
    )

    def rabbitSpec[F[_]](implicit A: Applicative[F]) = Service.Spec[F](
      "rabbit",
      NonEmptyList.of(Container.Spec("rabbitmq:3.6.10-management", Map.empty, Set(5672, 15672))),
      constantReady("rabbit ready check")
    )

    def default[F[_]: Applicative] = TestData[F](
      portRange,
      List(postgresSpec[F], rabbitSpec[F]),
      Nil
    )
  }

  case class RunningContainer(container: Container.Registered, serviceRef: Service.Ref)

  case class TestData[F[_]](portRange: Range, services: List[Service.Spec[F]], preExisting: List[RunningContainer])(
      implicit A: Applicative[F]) {

    def postgresSpec =
      services
        .find(_.name == TestData.postgresSpec[F].name)
        .getOrElse(fail(s"Undefined service spec ${TestData.postgresSpec.name}"))

    def rabbitSpec =
      services
        .find(_.name == TestData.rabbitSpec[F].name)
        .getOrElse(fail(s"Undefined service spec ${TestData.rabbitSpec.name}"))

    def withPostgresOnly = copy(services = List(postgresSpec))
    def withRabbitOnly   = copy(services = List(rabbitSpec))
  }

  class Dependencies[F[_]](
      val logger: Logger[F],
      val imageRegistry: ImageRegistry[F],
      val containerController: ContainerController[F],
      val scheduler: Scheduler[F])(implicit I: ImpureEffect[F], M: MonadError[F, Throwable], tag: AppTag) {

    def serviceRegistry(portRange: Range): ServiceRegistry[F] =
      new InMemoryServiceRegistry[F](portRange, logger)

    def serviceController(serviceRegistry: ServiceRegistry[F]): ServiceController[F] =
      new ServiceController[F](logger, serviceRegistry, containerController, scheduler)

    def runner(ctrl: ServiceController[F]): Runner[F] =
      new Runner[F](ctrl)
  }

  def withRunningServices[F[_]](deps: Dependencies[F])(testData: TestData[F])(
      runAssertion: (Runner[F], ServiceRegistry[F], Dependencies[F]) => F[Assertion])(
      implicit appTag: AppTag,
      ec: ExecutionContext,
      M: MonadError[F, Throwable],
      I: ImpureEffect[F]): F[Assertion] = {

    val serviceRegistry = deps.serviceRegistry(testData.portRange)
    val controller      = deps.serviceController(serviceRegistry)
    val runner          = deps.runner(controller)

    import cats.instances.list._
    import cats.syntax.foldable._
    import cats.syntax.functor._
    import cats.syntax.flatMap._

    //Note: we use foldM and not traverse here to avoid parallel execution
    // (which happens in the case of the default cats instance of `Traverse[Future]`)
    val setupRunningContainers =
      testData.preExisting.foldM(())((_, c) =>
        deps.containerController.fetchImageAndStartContainer(c.serviceRef, c.container).void)

    val setupServices = testData.services.foldM(())((_, spec) => runner.setUp(spec).void)

    setupRunningContainers >> setupServices >> runAssertion(runner, serviceRegistry, deps)
  }
}
