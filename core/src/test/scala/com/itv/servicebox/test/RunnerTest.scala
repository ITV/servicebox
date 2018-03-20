package com.itv.servicebox.test

import java.net.{ServerSocket, Socket}
import java.util.concurrent.atomic.AtomicInteger

import cats.MonadError
import cats.data.NonEmptyList
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

  def dependencies(implicit tag: AppTag): Dependencies[F]

  def withServices(testData: TestData[F])(f: (Runner[F], ServiceRegistry[F], Dependencies[F]) => F[Assertion])(
      implicit appTag: AppTag) =
    withRunningServices(dependencies)(testData)(f)

  def runServices(testData: TestData[F])(f: (Runner[F], ServiceRegistry[F], Dependencies[F]) => F[Assertion])(
      implicit appTag: AppTag) =
    I.runSync(withServices(testData)(f))

  import TestData.appTag

  "setUp" - {
    "initialises the service and updates the registry" in {
      //TODO: cleanup appTag mess!
      val testData = TestData.default[F].withPostgresOnly
      runServices(testData) { (_, serviceRegistry, deps) =>
        for {
          service         <- serviceRegistry.unsafeLookup(testData.postgresSpec)
          imageDownloaded <- deps.imageRegistry.imageExists(testData.postgresSpec.containers.head.imageName)

        } yield {
          service.endpoints.head.port should ===(testData.portRange.take(1).head)
          imageDownloaded should ===(true)
        }
      }
    }
    "assigns a host port for each container port in the spec" in {
      val data = TestData.default[F].withRabbitOnly
      runServices(data) { (_, serviceRegistry, _) =>
        for {
          service <- serviceRegistry.unsafeLookup(data.rabbitSpec)

        } yield {
          val hostPortsBound =
            data.portRange.take(data.rabbitSpec.containers.toList.flatMap(_.internalPorts).size).toList

          service.endpoints.toList.map(_.port) should ===(hostPortsBound)
        }
      }
    }
    "does not assign a port that is in-range, but bound to a running service" in {
      val server   = new ServerSocket(TestData.portRange.head)
      val testData = TestData.default[F].withRabbitOnly
      try {
        runServices(testData) { (_, serviceRegistry, _) =>
          for {
            service <- serviceRegistry.unsafeLookup(testData.rabbitSpec)
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
        )) { (_, serviceRegistry, deps) =>
        for {
          service           <- serviceRegistry.unsafeLookup(serviceSpec)
          runningContainers <- deps.containerController.runningContainers(service)
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

      //TODO: extract this logic into a testData syntax package
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

      runServices(updatedData) { (_, serviceRegistry, deps) =>
        for {
          service           <- serviceRegistry.unsafeLookup(serviceSpec)
          runningContainers <- deps.containerController.runningContainers(service)
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
      val rabbitSpec = TestData
        .rabbitSpec[F]
        .copy(
          readyCheck = ReadyCheck(_ => M.raiseError[Unit](new IllegalStateException(s"Cannot access test service")),
                                  10.millis,
                                  30.millis))

      I.runSync(withServices(testData.copy(services = List(rabbitSpec))) {
          case _ =>
            M.pure(Succeeded)
        }.attempt)
        .left
        .get shouldBe a[TimeoutException]
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
        }, 10.millis, 1.second))

      runServices(testData.copy(services = List(rabbitSpec))) { (_, serviceRegistry, deps) =>
        for {
          service           <- serviceRegistry.unsafeLookup(rabbitSpec)
          runningContainers <- deps.containerController.runningContainers(service)
        } yield {
          service.toSpec should ===(rabbitSpec)
          runningContainers should have size 1
        }
      }
    }
  }
  "tearDown" - {
    "shuts down the services and updates the registry" in {
      val testData = TestData.default[F]
      runServices(testData) { (runner, registry, deps) =>
        for {
          service           <- registry.unsafeLookup(testData.postgresSpec)
          _                 <- runner.tearDown(service)
          maybeSrv          <- registry.lookup(service.ref)
          runningContainers <- deps.containerController.runningContainers(service)
        } yield {
          maybeSrv should ===(None)
          runningContainers shouldBe empty
        }
      }
    }
  }
}
