package com.itv.servicebox.test

import cats.effect.IO
import com.itv.servicebox.algebra.State.Running
import com.itv.servicebox.algebra._
import org.scalactic.TypeCheckedTripleEquals
import org.scalatest.{Assertion, FreeSpec, Matchers, Succeeded}

trait RunnerTest extends FreeSpec with Matchers with TypeCheckedTripleEquals {
  def dependencies: Dependencies[IO]

  def withServices(testData: TestData[IO] = TestData.Default)(
      f: (Runner[IO], ServiceRegistry[IO], Dependencies[IO]) => IO[Assertion]) =
    withRunningServices(dependencies)(testData)(f)

  def runServices(testData: TestData[IO] = TestData.Default)(
      f: (Runner[IO], ServiceRegistry[IO], Dependencies[IO]) => IO[Assertion]) =
    withServices(testData)(f).unsafeRunSync()

  import TestData._
  "setUp" - {
    "initialises the service and updates the registry" in {
      runServices(TestData.Default.withPostgresOnly) { (_, serviceRegistry, deps) =>
        for {
          service         <- serviceRegistry.unsafeLookup(postgresSpec[IO].ref)
          imageDownloaded <- deps.imageRegistry.imageExists(postgresSpec[IO].containers.head.imageName)

        } yield {
          service.state should ===(State.Running)
          service.endpoints.head.port should ===(portRange.take(1).head)
          imageDownloaded should ===(true)
        }
      }
    }
    "assigns a host port for each container port in the spec" in {
      withServices(TestData.Default.withRabbitOnly) { (_, serviceRegistry, _) =>
        for {
          service <- serviceRegistry.unsafeLookup(rabbitSpec[IO].ref)

        } yield {
          val hostPortsBound = portRange.take(rabbitSpec[IO].containers.toList.flatMap(_.internalPorts).size).toList

          service.state should ===(State.Running)
          service.endpoints.toList.map(_.port) should ===(hostPortsBound)
        }
      }
    }

    "matches running containers" in {
      pending
    }

    "raises an error if the unallocated port range cannot fit the ports in the spec" in {
      withServices(TestData.Default.copy(portRange = TestData.portRange.take(1))) {
        case _ =>
          IO(Succeeded)
      }.attempt.unsafeRunSync().isLeft should ===(true)
    }
    "raises an error if a service container definition result in an empty port list" in {
      val containersNoInternalPorts = rabbitSpec.containers.map(c => c.copy(internalPorts = Nil))
      val spec                      = rabbitSpec[IO].copy(containers = containersNoInternalPorts)
      val containerRefs             = containersNoInternalPorts.toList.map(_.ref(rabbitSpec.ref))

      val expected =
        ServiceRegistry.EmptyPortList(containerRefs)

      withServices(TestData.Default.copy(services = List(spec))) {
        case _ =>
          IO(Succeeded)
      }.attempt.unsafeRunSync().left.get should ===(expected)
    }
  }
  "tearDown" - {
    "shuts down the services and updates the registry" in {
      withServices() { (runner, registry, _) =>
        for {
          service  <- registry.unsafeLookup(postgresSpec[IO].ref)
          _        <- runner.tearDown(service)
          maybeSrv <- registry.lookup(service.ref)
        } yield maybeSrv.forall(_.state != Running) should ===(true)
      }
    }
  }
}
