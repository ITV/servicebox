package com.itv.servicebox.algebra

import cats.effect.IO
import com.itv.servicebox.interpreter.{IORunner, InMemoryRegistry}
import com.itv.servicebox.fake
import org.scalatest.{FreeSpec, Matchers}
import Service.Status

import scala.concurrent.duration._
import cats.data.NonEmptyList
import org.scalactic.TypeCheckedTripleEquals

class RunnerTest extends FreeSpec with Matchers with TypeCheckedTripleEquals {
  val appTag     = AppTag("test")
  val portRange  = 49152 to 65535
  val registry   = new InMemoryRegistry(portRange)
  val controller = new fake.Controller()
  val runner     = new IORunner(registry, controller)
  val spec: Service.Spec[IO] = Service.Spec(
    appTag,
    "db",
    NonEmptyList.of(Container.Spec("postgres:9.5.4", Map("POSTGRES_DB" -> appTag.value), List(5432))),
    Service.ReadyCheck[IO](_ => IO.pure(true), 3.millis)
  )

  "setUp" - {
    "when service is not running" - {
      "initialises the service and updates the registry" in {
        (for {
          _       <- runner.setUp(spec)
          service <- registry.lookup(spec.id)

        } yield {
          service.get.status should ===(Status.Running)
          service.get.endpoints.head.port should ===(portRange.head)

        }).unsafeRunSync()
      }
      "assigns a host port for each container port in the spec" in pending
      "raises an error if the unallocated port range cannot fit the ports in the spec" in pending
    }
    "when service is paused" in {}
  }
  "tierDown" - {
    "shuts down the services and updates the registry" in pending
  }
}
