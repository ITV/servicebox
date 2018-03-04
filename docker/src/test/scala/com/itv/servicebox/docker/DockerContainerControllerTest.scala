package com.itv.servicebox.docker

import cats.data.NonEmptyList
import cats.effect.IO
import com.itv.servicebox.algebra._
import com.itv.servicebox.interpreter._
import com.spotify.docker.client.DefaultDockerClient
import org.scalatest.{Assertion, FreeSpec, Matchers}

import scala.concurrent.duration._

class DockerContainerControllerTest extends FreeSpec with Matchers {
  "does stuff" in {
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
      val dockerClient    = DefaultDockerClient.fromEnv.build
      val serviceRegistry = new InMemoryServiceRegistry(portRange, IOLogger)
      val imageRegistry   = new DockerImageRegistry[IO](dockerClient, IOLogger)
      val containerController =
        new DockerContainerController[IO](dockerClient, imageRegistry, IOLogger)
      val controller = new IOServiceController(serviceRegistry, containerController)
      val runner     = new IORunner(controller)
      f(runner, serviceRegistry, imageRegistry)
    }

    withRunner(portRange) {
      case (runner, r, ir) =>
        for {
          _ <- runner.setUp(postgresSpec)
        } yield true should ===(true)
    }.unsafeRunSync()
  }
}
