package example

import cats.data.NonEmptyList
import cats.syntax.flatMap._
import cats.effect.IO

import scala.concurrent.duration._
import com.itv.servicebox.algebra._
import com.itv.servicebox.interpreter._
import com.itv.servicebox.docker
import ServiceRegistry.Endpoints
import com.itv.servicebox.algebra.Runner

import scala.concurrent.ExecutionContext.Implicits.global
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

class KVStoreTest extends FlatSpec with Matchers with BeforeAndAfterAll {
  implicit val tag: AppTag = AppTag("com.example", "some-app")

  private lazy val runnerAndConfig: (Runner[IO], DbConfig) = {
    val baseConfig = DbConfig("localhost", 5432, "kvstore", "postgres", "")

    def dbConnect(endpoints: Endpoints): IO[Unit] =
      for {
        _ <- IOLogger.info("Attempting to connect to DB ...")
        serviceConfig = baseConfig.copy(host = endpoints.head.host, port = endpoints.head.port)
        _ <- IOLogger.info(s"using config: $serviceConfig")
        _ <- pingDb(serviceConfig)
        _ <- IOLogger.info("... connected")
      } yield ()

    val postgres = Service.Spec[IO](
      "Postgres",
      NonEmptyList.of(
        Container.Spec("postgres:9.5.4",
          Map(
            "POSTGRES_DB" -> baseConfig.dbName,
            "POSTGRES_PASSWORD" -> baseConfig.password),
          Set(5432))),
      Service.ReadyCheck[IO](dbConnect, 10.millis, 30.seconds)
    )

    val runner = docker.runner()(postgres)
    val endpoints = runner.setUp.unsafeRunSync().head.endpoints
    val config = baseConfig.copy(host = endpoints.head.host, port = endpoints.head.port)
    (runner, config)
  }

  def serviceConfig = runnerAndConfig._2
  def serviceRunner = runnerAndConfig._1

  it should "set and get a key" in {
    val store = KVStore(serviceConfig)
    val setAndGet = store.set("foo", "bar") >> store.get("foo")
    setAndGet.unsafeRunSync() should ===(Some("bar"))
  }

  it should "allow setting the same key multiple times" in {
    val store = KVStore(serviceConfig)
    val setTwiceAndGet = store.set("foo", "baz") >> store.set("foo", "baz") >> store.get("foo")
    setTwiceAndGet.unsafeRunSync() should ===(Some("baz"))
  }

  // This is an alternative approach to the shutdown hook documented in the README
  override def afterAll() = {
    serviceRunner.tearDown.unsafeRunSync()
  }
}
