package com.itv.servicebox.algebra

import cats.effect.IO
import com.itv.servicebox.interpreter.{IORunner, InMemoryRegistry, IOServiceController}
import com.itv.servicebox.fake
import org.scalatest.{Assertion, FreeSpec, Matchers}

import scala.concurrent.duration._
import cats.data.NonEmptyList
import com.itv.servicebox.algebra.Status.Running
import org.scalactic.TypeCheckedTripleEquals

class RunnerTest extends FreeSpec with Matchers with TypeCheckedTripleEquals {
  val appTag    = AppTag("test")
  val portRange = 49152 to 49162

  val postgresSpec = Service.Spec[IO](
    appTag,
    "db",
    NonEmptyList.of(Container.Spec("postgres:9.5.4", Map("POSTGRES_DB" -> appTag.value), List(5432))),
    Service.ReadyCheck[IO](_ => IO.pure(true), 3.millis)
  )

  val rabbitSpec = Service.Spec[IO](
    appTag,
    "rabbit",
    NonEmptyList.of(Container.Spec("rabbitmq:3.6.10-management", Map.empty, List(5672, 15672))),
    Service.ReadyCheck[IO](_ => IO.pure(true), 3.millis)
  )

  def withRunner(portRange: Range = portRange)(f: (Runner[IO], Registry[IO]) => IO[Assertion]): IO[Assertion] = {
    val registry            = new InMemoryRegistry(portRange)
    val containerController = new fake.ContainerController(CleanupStrategy.Pause, Map.empty, registry)
    val controller          = new IOServiceController(registry, containerController)
    val runner              = new IORunner(controller)
    f(runner, registry)
  }

  "setUp" - {
    "initialises the service and updates the registry" in {
      withRunner() { (runner, registry) =>
        for {
          _       <- runner.setUp(postgresSpec)
          service <- registry.unsafeLookup(postgresSpec.ref)

        } yield {
          service.status should ===(Status.Running)
          service.endpoints.head.port should ===(portRange.take(1).head)

        }
      }.unsafeRunSync()
    }
    "assigns a host port for each container port in the spec" in {
      withRunner() { (runner, registry) =>
        for {
          _       <- runner.setUp(rabbitSpec)
          service <- registry.unsafeLookup(rabbitSpec.ref)

        } yield {
          val hostPortsBound = portRange.take(rabbitSpec.containers.toList.flatMap(_.internalPorts).size).toList

          service.status should ===(Status.Running)
          service.endpoints.toList.map(_.port) should ===(hostPortsBound)
        }
      }.unsafeRunSync()
    }

    "raises an error if the unallocated port range cannot fit the ports in the spec" in {
      withRunner(portRange.take(1)) { (runner, _) =>
        for {
          result <- runner.setUp(rabbitSpec).attempt
        } yield result.isLeft should ===(true)
      }.unsafeRunSync()
    }
  }
  "tearDown" - {
    "shuts down the services and updates the registry" in {
      withRunner() { (runner, registry) =>
        for {
          service  <- runner.setUp(postgresSpec)
          _        <- runner.tearDown(service)
          maybeSrv <- registry.lookup(service.ref)
        } yield maybeSrv.forall(_.status != Running) should ===(true)
      }.unsafeRunSync()
    }
  }
}
