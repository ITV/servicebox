package com.itv.servicebox.algebra

import cats.effect.IO
import com.itv.servicebox.interpreter.{IOLogger, IORunner, IOServiceController, InMemoryServiceRegistry}
import com.itv.servicebox.fake
import org.scalatest.{Assertion, FreeSpec, Matchers}

import scala.concurrent.duration._
import cats.data.NonEmptyList
import com.itv.servicebox.algebra.State.Running
import com.itv.servicebox.fake.InMemoryImageRegistry
import org.scalactic.TypeCheckedTripleEquals

class RunnerTest extends FreeSpec with Matchers with TypeCheckedTripleEquals {
  val appTag    = AppTag("org", "test")
  val portRange = 49152 to 49162

  val postgresSpec = Service.Spec[IO](
    appTag,
    "db",
    NonEmptyList.of(Container.Spec("postgres:9.5.4", Map("POSTGRES_DB" -> appTag.appName), List(5432))),
    Service.ReadyCheck[IO](_ => IO.pure(true), 3.millis)
  )

  val rabbitSpec = Service.Spec[IO](
    appTag,
    "rabbit",
    NonEmptyList.of(Container.Spec("rabbitmq:3.6.10-management", Map.empty, List(5672, 15672))),
    Service.ReadyCheck[IO](_ => IO.pure(true), 3.millis)
  )

  def withRunner(portRange: Range = portRange)(
      f: (Runner[IO], ServiceRegistry[IO], ImageRegistry[IO]) => IO[Assertion]): IO[Assertion] = {
    val serviceRegistry = new InMemoryServiceRegistry(portRange, IOLogger)
    val imageRegistry   = new InMemoryImageRegistry(IOLogger)
    val containerController =
      new fake.ContainerController(imageRegistry, Map.empty, IOLogger)
    val controller = new IOServiceController(serviceRegistry, containerController)
    val runner     = new IORunner(controller)
    f(runner, serviceRegistry, imageRegistry)
  }

  "setUp" - {
    "initialises the service and updates the registry" in {
      withRunner() { (runner, serviceRegistry, imageRegistry) =>
        for {
          _               <- runner.setUp(postgresSpec)
          service         <- serviceRegistry.unsafeLookup(postgresSpec.ref)
          imageDownloaded <- imageRegistry.imageExists(postgresSpec.containers.head.imageName)

        } yield {
          service.state should ===(State.Running)
          service.endpoints.head.port should ===(portRange.take(1).head)
          imageDownloaded should ===(true)
        }
      }.unsafeRunSync()
    }
    "assigns a host port for each container port in the spec" in {
      withRunner() { (runner, srvRegistry, _) =>
        for {
          _       <- runner.setUp(rabbitSpec)
          service <- srvRegistry.unsafeLookup(rabbitSpec.ref)

        } yield {
          val hostPortsBound = portRange.take(rabbitSpec.containers.toList.flatMap(_.internalPorts).size).toList

          service.state should ===(State.Running)
          service.endpoints.toList.map(_.port) should ===(hostPortsBound)
        }
      }.unsafeRunSync()
    }

    "raises an error if the unallocated port range cannot fit the ports in the spec" in {
      withRunner(portRange.take(1)) { (runner, _, _) =>
        for {
          result <- runner.setUp(rabbitSpec).attempt
        } yield result.isLeft should ===(true)
      }.unsafeRunSync()
    }
    "raises an error if a service containers definition result in an empty port list" in {
      withRunner() { (runner, _, _) =>
        val containers = rabbitSpec.containers.map(c => c.copy(internalPorts = Nil))
        for {
          result <- runner.setUp(rabbitSpec.copy(containers = containers)).attempt
          expected = ServiceRegistry.EmptyPortList(containers.toList.map(_.ref(rabbitSpec.ref)))
        } yield result.left.get should ===(expected)
      }
    }
  }
  "tearDown" - {
    "shuts down the services and updates the registry" in {
      withRunner() { (runner, registry, _) =>
        for {
          service  <- runner.setUp(postgresSpec)
          _        <- runner.tearDown(service)
          maybeSrv <- registry.lookup(service.ref)
        } yield maybeSrv.forall(_.state != Running) should ===(true)
      }.unsafeRunSync()
    }
  }
}
