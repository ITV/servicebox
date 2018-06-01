package com.itv.servicebox

import cats.data.NonEmptyList
import cats.{Applicative, Functor, MonadError}
import com.itv.servicebox.algebra.{ImpureEffect, InMemoryServiceRegistry, _}
import org.scalatest.Assertion
import org.scalatest.Matchers._
import cats.syntax.show._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

package object test {
  object TestData {
    val portRange       = 49162 to 49192
    implicit val appTag = AppTag("org", "test")

    def constantReady[F[_]](label: String)(implicit A: Applicative[F]): Service.ReadyCheck[F] =
      Service.ReadyCheck[F](_ => A.unit, 5.millis, 1.second)

    def postgresSpec[F[_]: Applicative] = Service.Spec[F](
      "db",
      NonEmptyList.of(Container.Spec("postgres:9.5.4", Map("POSTGRES_DB" -> appTag.appName), Set(5432), None)),
      constantReady[F]("postgres ready check")
    )

    def rabbitSpec[F[_]](implicit A: Applicative[F]) = Service.Spec[F](
      "rabbit",
      NonEmptyList.of(Container.Spec("rabbitmq:3.6.10-management", Map.empty, Set(5672, 15672), None)),
      constantReady("rabbit ready check")
    )

    def ncSpec[F[_]](implicit A: Applicative[F]) = Service.Spec[F](
      "netcat-service",
      NonEmptyList.of(Container.Spec("subfuzion/netcat", Map.empty, Set(8080), Some(NonEmptyList.of("-l", "8080")))),
      constantReady("netcat ready check")
    )

    def default[F[_]: Applicative] = TestData[F](
      portRange,
      List(postgresSpec[F], rabbitSpec[F]).map(s => s.ref -> s).toMap,
      Nil
    )

    def apply[F[_]](spec: Service.Spec[F]*)(implicit A: Applicative[F]): TestData[F] =
      new TestData(portRange, spec.map(s => s.ref -> s).toMap, Nil)

    def apply[F[_]](portRange: Range, specs: List[Service.Spec[F]], preExisting: List[RunningContainer])(
        implicit A: Applicative[F]): TestData[F] =
      new TestData(portRange, specs.map(s => s.ref -> s).toMap, Nil)
  }

  case class RunningContainer(container: Container.Registered, serviceRef: Service.Ref)

  case class TestData[F[_]](portRange: Range,
                            servicesByRef: Map[Service.Ref, Service.Spec[F]],
                            preExisting: List[RunningContainer])(implicit A: Applicative[F]) {
    private val AL = algebra.Lenses
    private val TL = test.Lenses

    //TODO: fix this mess!
    implicit val appTag: AppTag = TestData.appTag

    def services: List[Service.Spec[F]] = servicesByRef.values.toList

    def withSpecs(specs: Service.Spec[F]*): TestData[F] =
      TL.services.set(specs.map(s => s.ref -> s).toMap)(this)

    def withPreExisting(containers: RunningContainer*): TestData[F] =
      TL.preExisting.set(containers.toList)(this)

    def modifyPortRange(f: Range => Range): TestData[F] =
      TL.portRange.modify(f)(this)

    def serviceAt(serviceRef: Service.Ref): Service.Spec[F] =
      TL.serviceAt(serviceRef)
        .getOption(this)
        .getOrElse(fail(s"cannot find service ${serviceRef.show} in $this"))

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

    def runner(ctrl: ServiceController[F], registry: ServiceRegistry[F], services: List[Service.Spec[F]]): Runner[F] =
      new Runner[F](ctrl, registry)(services: _*)
  }

  case class TestEnv[F[_]](
      deps: Dependencies[F],
      serviceRegistry: ServiceRegistry[F],
      runner: Runner[F],
      runtimeInfo: Map[Service.Ref, Service.RuntimeInfo])(implicit I: ImpureEffect[F], F: Functor[F])

  def withRunningServices[F[_]](deps: Dependencies[F])(testData: TestData[F])(runAssertion: TestEnv[F] => F[Assertion])(
      implicit appTag: AppTag,
      ec: ExecutionContext,
      M: MonadError[F, Throwable],
      I: ImpureEffect[F]): F[Assertion] = {

    val serviceRegistry = deps.serviceRegistry(testData.portRange)
    val controller      = deps.serviceController(serviceRegistry)
    val runner          = deps.runner(controller, serviceRegistry, testData.services)

    import cats.instances.list._
    import cats.syntax.flatMap._
    import cats.syntax.foldable._
    import cats.syntax.functor._

    //Note: we use foldM and not traverse here to avoid parallel execution
    // (which happens in the case of the default cats instance of `Traverse[Future]`)
    val setupRunningContainers =
      testData.preExisting.foldM(())((_, c) =>
        deps.containerController.fetchImageAndStartContainer(c.serviceRef, c.container).void)

    setupRunningContainers >> runner.setupWithRuntimeInfo >>= { x =>
      val runtimeInfo = x.map { case (registered, info) => registered.ref -> info }.toMap
      runAssertion(TestEnv[F](deps, serviceRegistry, runner, runtimeInfo))
    }
  }
}
