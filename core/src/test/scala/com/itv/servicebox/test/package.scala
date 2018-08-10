package com.itv.servicebox

import cats.data.NonEmptyList
import cats.effect.Effect
import cats.{Applicative, Functor, MonadError}
import com.itv.servicebox.algebra._
import org.scalatest.Assertion
import org.scalatest.Matchers._
import cats.syntax.show._
import com.itv.servicebox.fake.TestNetworkController

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

package object test {
  object TestData {
    val portRange       = 49162 to 49192
    implicit val appTag = AppTag("com", "example")

    def constantReady[F[_]](label: String)(implicit A: Applicative[F]): Service.ReadyCheck[F] =
      Service.ReadyCheck[F](_ => A.unit, 5.millis, 1.second)

    def postgresSpec[F[_]: Applicative] = Service.Spec[F](
      "db",
      NonEmptyList.of(
        Container
          .Spec("postgres:9.5.4", Map("POSTGRES_DB" -> appTag.appName), Set(PortSpec.autoAssign(5432)), None, None)),
      constantReady[F]("postgres ready check")
    )

    def rabbitSpec[F[_]](implicit A: Applicative[F]) = Service.Spec[F](
      "rabbit",
      NonEmptyList.of(
        Container.Spec("rabbitmq:3.6.10-management",
                       Map.empty[String, String],
                       Set(PortSpec.autoAssign(5672), PortSpec.autoAssign(15672)),
                       None,
                       None)),
      constantReady("rabbit ready check")
    )

    def ncSpec[F[_]](implicit A: Applicative[F]) = Service.Spec[F](
      "netcat-service",
      NonEmptyList.of(
        Container
          .Spec("subfuzion/netcat",
                Map.empty[String, String],
                Set(PortSpec.autoAssign(8080)),
                Some(NonEmptyList.of("-l", "8080")),
                None)),
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
      TestData(portRange, specs.map(s => s.ref -> s).toMap, preExisting)
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
      val networkController: TestNetworkController[F],
      val containerController: ContainerController[F],
      val scheduler: Scheduler[F])(implicit E: Effect[F], M: MonadError[F, Throwable], tag: AppTag) {

    def serviceRegistry(portRange: Range): ServiceRegistry[F] =
      new InMemoryServiceRegistry[F](portRange, logger)

    def serviceController(serviceRegistry: ServiceRegistry[F]): ServiceController[F] =
      new ServiceController[F](logger, serviceRegistry, containerController, scheduler)

    def runner(srvCtrl: ServiceController[F],
               networkCtrl: TestNetworkController[F],
               registry: ServiceRegistry[F],
               services: List[Service.Spec[F]]): Runner[F] =
      new Runner[F](srvCtrl, networkCtrl, registry)(services: _*)
  }

  case class TestEnv[F[_]](deps: Dependencies[F],
                           serviceRegistry: ServiceRegistry[F],
                           runner: Runner[F],
                           runtimeInfo: Map[Service.Ref, Service.RuntimeInfo],
                           preExisting: List[RunningContainer])(implicit I: Effect[F], F: Functor[F])

  def withRunningServices[F[_]](deps: Dependencies[F])(testData: TestData[F])(runAssertion: TestEnv[F] => F[Assertion])(
      implicit appTag: AppTag,
      ec: ExecutionContext,
      M: MonadError[F, Throwable],
      I: Effect[F]): F[Assertion] = {

    val serviceRegistry = deps.serviceRegistry(testData.portRange)
    val srvCtrl         = deps.serviceController(serviceRegistry)
    val networkCtrl     = deps.networkController
    val runner          = deps.runner(srvCtrl, networkCtrl, serviceRegistry, testData.services)

    import cats.instances.list._
    import cats.syntax.flatMap._
    import cats.syntax.foldable._
    import cats.syntax.functor._

    val setupRunningContainers =
      testData.preExisting.foldM(())(
        (_, c) =>
          //TODO: allow to connect to a specific network
          deps.containerController.fetchImageAndStartContainer(c.serviceRef, c.container).void)

    networkCtrl.createNetwork >> setupRunningContainers >> runner.setupWithRuntimeInfo >>= { x =>
      val runtimeInfo = x.map { case (registered, info) => registered.ref -> info }.toMap
      runAssertion(TestEnv[F](deps, serviceRegistry, runner, runtimeInfo, testData.preExisting))
    }
  }
}
