package com.itv.servicebox.test

import java.net.{ServerSocket, Socket}
import java.util.concurrent.atomic.AtomicInteger

import cats.MonadError
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.itv.servicebox.algebra.Service.ReadyCheck
import com.itv.servicebox.algebra._
import org.scalactic.TypeCheckedTripleEquals
import org.scalatest.{Assertion, FreeSpec, Matchers, Succeeded}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, TimeoutException}
import scala.util.{Success, Try}

abstract class RunnerTest[F[_]](implicit ec: ExecutionContext, M: MonadError[F, Throwable], I: ImpureEffect[F])
    extends FreeSpec
    with Matchers
    with TypeCheckedTripleEquals {

  //TODO: cleanup appTag mess!
  def dependencies(implicit tag: AppTag): Dependencies[F]

  def withServices(testData: TestData[F])(f: TestEnv[F] => F[Assertion])(implicit appTag: AppTag) =
    withRunningServices(dependencies)(testData)(f)

  def runServices(testData: TestData[F])(f: TestEnv[F] => F[Assertion])(implicit appTag: AppTag) =
    I.runSync(withServices(testData)(f))

  import TestData.appTag

  "setUp" - {
    "initialises the service and updates the registry" in {
      val testData = TestData.default[F].withPostgresOnly
      runServices(testData) { env =>
        for {
          service         <- env.serviceRegistry.unsafeLookup(testData.postgresSpec)
          imageDownloaded <- env.deps.imageRegistry.imageExists(testData.postgresSpec.containers.head.imageName)

        } yield {
          service.endpoints.head.port should ===(testData.portRange.take(1).head)
          imageDownloaded should ===(true)
        }
      }
    }

    "returns a list of registered services, preserving the order in which they are specified" in {
      val testData = TestData.default[F]
      runServices(testData) { env =>
        for {
          registered <- env.runner.setUp

        } yield {
          registered.map(_.ref) should ===(testData.services.map(_.ref))
        }
      }
    }

    "assigns a host port for each container port in the spec" in {
      val data0 = TestData.default[F].withRabbitOnly
      val data  = data0.copy(portRange = data0.portRange.reverse)
      runServices(data) { env =>
        for {
          service <- env.serviceRegistry.unsafeLookup(data.rabbitSpec)

        } yield {
          val hostPortsBound =
            data.portRange.take(data.rabbitSpec.containers.toList.flatMap(_.internalPorts).size).toList

          service.endpoints.toList.map(_.port) should ===(hostPortsBound)
        }
      }
    }
    "does not assign a port that is in-range, but bound to a running service" in {
      val testData = TestData.default[F].withRabbitOnly
      val server   = new ServerSocket(TestData.portRange.head)
      try {
        runServices(testData) { env =>
          for {
            service <- env.serviceRegistry.unsafeLookup(testData.rabbitSpec)
          } yield {
            Try(new Socket("localhost", TestData.portRange.head).close()) should ===(Success(()))
            service.endpoints.toList.map(_.port) should ===(testData.portRange.slice(1, 3).toList)
          }
        }
      } finally {
        server.close()
      }
    }
    "matches running containers" in {
      val testData      = TestData.default[F]
      val serviceSpec   = testData.rabbitSpec
      val containerSpec = serviceSpec.containers.head

      val portsMapped = containerSpec.internalPorts.size

      val mappings = containerSpec.internalPorts.zip(TestData.portRange.take(portsMapped)).map(_.swap)

      val registered = Container.Registered(
        containerSpec.ref(serviceSpec.ref),
        containerSpec.imageName,
        containerSpec.env,
        mappings
      )

      val preExisting = List(RunningContainer(registered, serviceSpec.ref))

      runServices(
        testData.copy(
          preExisting = preExisting,
          portRange = TestData.portRange.drop(portsMapped)
        )) { env =>
        for {
          service           <- env.serviceRegistry.unsafeLookup(serviceSpec)
          runningContainers <- env.deps.containerController.runningContainers(service)
        } yield {
          service.containers.toList should ===(List(registered))
          runningContainers should have size 1
          runningContainers should ===(preExisting.map(_.container))
          runningContainers should ===(service.containers.toList)
        }
      }
    }

    "tears down containers that do not match the spec" in {
      val testData      = TestData.default[F].withPostgresOnly
      val serviceSpec   = testData.postgresSpec
      val containerSpec = serviceSpec.containers.head

//      TODO: extract this logic into a testData syntax package
      val portsMapped = containerSpec.internalPorts.size
      val mappings    = containerSpec.internalPorts.zip(testData.portRange.take(portsMapped)).map(_.swap)

      val runningPostgres = Container.Registered(
        containerSpec.ref(serviceSpec.ref),
        containerSpec.imageName,
        Map("POSTGRES_DB" -> "other-db"),
        mappings
      )

      val preExisting =
        List(RunningContainer(runningPostgres, serviceSpec.ref))

      val updatedData = TestData[F](testData.portRange.drop(portsMapped), List(serviceSpec), preExisting)

      runServices(updatedData) { env =>
        for {
          service           <- env.serviceRegistry.unsafeLookup(serviceSpec)
          runningContainers <- env.deps.containerController.runningContainers(service)
        } yield {
          runningContainers should have size 1
          runningContainers should !==(preExisting.map(_.container))
          service.containers.map(_.toSpec) should ===(updatedData.services.head.containers)
        }
      }
    }
    "raises an error if the unallocated port range cannot fit the ports in the spec" in {
      I.runSync(withServices(TestData.default[F].copy(portRange = TestData.portRange.take(1))) {
          case _ =>
            M.pure(Succeeded)
        }.attempt)
        .isLeft should ===(true)
    }
    "raises an error if a service container definition result in an empty port list" in {
      val testData                  = TestData.default[F].withRabbitOnly
      val containersNoInternalPorts = testData.rabbitSpec.containers.map(c => c.copy(internalPorts = Set.empty))
      val spec                      = testData.rabbitSpec.copy(containers = containersNoInternalPorts)
      val containerRefs             = containersNoInternalPorts.toList.map(_.ref(testData.rabbitSpec.ref))

      val expected =
        ServiceRegistry.EmptyPortList(containerRefs)

      I.runSync(withServices(testData.copy(services = List(spec))) {
          case _ =>
            M.pure(Succeeded)
        }.attempt)
        .left
        .get should ===(expected)
    }

    "raises an error if a service ready-check times out" in {
      val testData = TestData.default[F]
      val counter  = new AtomicInteger(0)
      val rabbitSpec = TestData
        .rabbitSpec[F]
        .copy(
          readyCheck = ReadyCheck(_ =>
                                    I.lift(counter.getAndIncrement()) >> M.raiseError[Unit](
                                      new IllegalStateException(s"Cannot access test service")),
                                  100.millis,
                                  1.second))

      val result = I.runSync(
        withServices(testData.copy(services = List(rabbitSpec))) {
          case _ => M.pure(Succeeded)
        }.attempt
      )

      result.left.get shouldBe a[TimeoutException]
      counter.get should ===(10 +- 5)
    }

    "recovers from errors when ready-checks eventually succeed" in {
      val testData = TestData.default[F]
      val counter  = new AtomicInteger(0)
      val rabbitSpec = TestData
        .rabbitSpec[F]
        .copy(readyCheck = ReadyCheck(_ => {
          if (counter.getAndIncrement() < 3)
            M.raiseError[Unit](new IllegalStateException(s"Cannot access test service"))
          else
            M.pure(())
        }, 100.millis, 1.second))

      I.runSync(
        withServices(testData.copy(services = List(rabbitSpec))) { env =>
          for {
            service           <- env.serviceRegistry.unsafeLookup(rabbitSpec)
            runningContainers <- env.deps.containerController.runningContainers(service)
          } yield {
            service.toSpec should ===(rabbitSpec)
            runningContainers should have size 1
            counter.get() should ===(4)
          }
        }
      )
    }
  }

  "tearDown" - {
    "shuts down the services and updates the registry" in {
      val testData = TestData.default[F]
      runServices(testData) { env =>
        for {
          service           <- env.serviceRegistry.unsafeLookup(testData.postgresSpec)
          _                 <- env.runner.tearDown
          maybeSrv          <- env.serviceRegistry.lookup(service.ref)
          runningContainers <- env.deps.containerController.runningContainers(service)
        } yield {
          maybeSrv should ===(None)
          runningContainers shouldBe empty
        }
      }
    }
  }
}
