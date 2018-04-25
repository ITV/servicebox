package example

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.flatMap._
import cats.syntax.monadError._
import com.itv.servicebox.algebra.ServiceRegistry.Endpoints
import com.itv.servicebox.algebra.{Runner, _}
import com.itv.servicebox.docker
import com.itv.servicebox.interpreter._
import org.influxdb.InfluxDBFactory
import org.influxdb.dto.{Query, QueryResult}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class KVStoreTest extends FlatSpec with Matchers with BeforeAndAfterAll {
  implicit val tag: AppTag = AppTag("com.example", "some-app")

  private lazy val runnerAndConfig: (Runner[IO], Config) = {
    val basePostgresConfig = DbConfig("localhost", 5432, "kvstore", "postgres", "")
    val baseInfluxConfig   = InfluxDBConfig("localhost", 8086, "metrics", "example", "example")

    def dbConnect(endpoints: Endpoints): IO[Unit] =
      for {
        _ <- IOLogger.info("Attempting to connect to DB ...")
        serviceConfig = basePostgresConfig.copy(host = endpoints.head.host, port = endpoints.head.port)
        _ <- IOLogger.info(s"using config: $serviceConfig")
        _ <- pg.pingDb(serviceConfig)
        _ <- IOLogger.info("... connected")
      } yield ()

    val postgres = Service.Spec[IO](
      "Postgres",
      NonEmptyList.of(
        Container.Spec("postgres:9.5.4",
                       Map("POSTGRES_DB"       -> basePostgresConfig.dbName,
                           "POSTGRES_PASSWORD" -> basePostgresConfig.password),
                       Set(5432))),
      Service.ReadyCheck[IO](dbConnect, 10.millis, 30.seconds)
    )

    val influxDb = {
      def pingAndInit(endpoints: Endpoints): IO[Unit] = {
        val config = baseInfluxConfig.copy(host = endpoints.head.host, port = endpoints.head.port)
        val client = InfluxDBFactory.connect(config.toUrl, config.user, config.password)
        val q      = influx.Queries(config.dbName)

        def queryError(res: QueryResult) =
          new IllegalArgumentException(s"bad InfluxDB query: ${res.getError}")

        for {
          _ <- IO(Thread.sleep(1000))
          _ <- IO(client.ping()).ensure(new IllegalStateException("Bad connection status"))(_.isGood)
          _ <- IO(client.query(q.dropPolicy)).ensureOr(queryError)(res => !res.hasError)
          _ <- IO(client.query(q.createPolicy)).ensureOr(queryError)(res => !res.hasError)
        } yield ()

      }

      Service.Spec[IO](
        "influxdb",
        NonEmptyList.of(
          Container.Spec(
            "influxdb:1.4.3",
            Map("INFLUXDB_DB"            -> baseInfluxConfig.dbName,
                "INFLUXDB_USER"          -> baseInfluxConfig.user,
                "INFLUXDB_USER_PASSWORD" -> baseInfluxConfig.password),
            Set(8086, 8088)
          )
        ),
        Service.ReadyCheck(pingAndInit, 5.seconds, 3.minutes)
      )
    }

    val runner                           = docker.runner()(postgres, influxDb)
    val pgEndpoints :: nfxEndpoints :: _ = runner.setUp.map(_.map(_.endpoints)).unsafeRunSync()
    val dbConfig                         = basePostgresConfig.copy(host = pgEndpoints.head.host, port = pgEndpoints.head.port)
    val metrixConfig                     = baseInfluxConfig.copy(host = nfxEndpoints.head.host, port = nfxEndpoints.head.port)
    (runner, Config(dbConfig, metrixConfig))
  }

  def serviceConfig = runnerAndConfig._2
  def serviceRunner = runnerAndConfig._1

  it should "set and get a key" in {
    val store     = KVStore(serviceConfig)
    val setAndGet = store.set("foo", "bar") >> store.get("foo")
    setAndGet.unsafeRunSync() should ===(Some("bar"))
  }

  it should "allow setting the same key multiple times" in {
    val store          = KVStore(serviceConfig)
    val setTwiceAndGet = store.set("foo", "baz") >> store.set("foo", "baz") >> store.get("foo")
    setTwiceAndGet.unsafeRunSync() should ===(Some("baz"))
  }

  // This is an alternative approach to the shutdown hook documented in the README
  override def afterAll() =
    serviceRunner.tearDown.unsafeRunSync()
}
