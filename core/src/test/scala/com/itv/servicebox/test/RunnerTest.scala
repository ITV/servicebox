package com.itv.servicebox.test

import cats.Monad
import cats.data.NonEmptyList
import cats.effect.IO
import com.itv.servicebox.algebra.State.Running
import com.itv.servicebox.algebra._
import com.itv.servicebox.fake
import com.itv.servicebox.fake.InMemoryImageRegistry
import com.itv.servicebox.interpreter.{IOLogger, IORunner, IOServiceController, InMemoryServiceRegistry}
import org.scalactic.TypeCheckedTripleEquals
import org.scalatest.{Assertion, FreeSpec, Matchers}

import scala.concurrent.duration._

trait RunnerTest extends FreeSpec with Matchers with TypeCheckedTripleEquals {
  def dependencies: Dependencies[IO]

  def withRunner(portRange: Range = TestData.portRange) =
    withDependencies(dependencies)(
      TestData[IO](portRange = portRange,
                   List(
                     TestData.postgresSpec[IO],
                     TestData.rabbitSpec[IO]
                   )))(_)

  import TestData._
  "setUp" - {
    "initialises the service and updates the registry" in {
      withRunner() { (runner, serviceRegistry, deps) =>
        for {
          _               <- runner.setUp(postgresSpec[IO])
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
      withRunner() { (runner, serviceRegistry, _) =>
        for {
          _       <- runner.setUp(rabbitSpec[IO])
          service <- serviceRegistry.unsafeLookup(rabbitSpec[IO].ref)

        } yield {
          val hostPortsBound = portRange.take(rabbitSpec[IO].containers.toList.flatMap(_.internalPorts).size).toList

          service.state should ===(State.Running)
          service.endpoints.toList.map(_.port) should ===(hostPortsBound)
        }
      }
    }

    "raises an error if the unallocated port range cannot fit the ports in the spec" in {
      withRunner(TestData.portRange.take(1)) { (runner, _, _) =>
        for {
          result <- runner.setUp(rabbitSpec[IO]).attempt
        } yield result.isLeft should ===(true)
      }
    }
    "raises an error if a service container definition result in an empty port list" in {
      withRunner() { (runner, _, _) =>
        val containersNoInternalPorts = rabbitSpec.containers.map(c => c.copy(internalPorts = Nil))
        for {
          result <- runner.setUp(rabbitSpec[IO].copy(containers = containersNoInternalPorts)).attempt
          expected = ServiceRegistry.EmptyPortList(containersNoInternalPorts.toList.map(_.ref(rabbitSpec.ref)))
        } yield result.left.get should ===(expected)
      }
    }
  }
  "tearDown" - {
    "shuts down the services and updates the registry" in {
      withRunner() { (runner, registry, _) =>
        for {
          service  <- runner.setUp(postgresSpec[IO])
          _        <- runner.tearDown(service)
          maybeSrv <- registry.lookup(service.ref)
        } yield maybeSrv.forall(_.state != Running) should ===(true)
      }
    }
  }
}
