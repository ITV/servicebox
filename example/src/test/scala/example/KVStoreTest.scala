package example

import cats.data.NonEmptyList
import cats.effect.{IO, Timer}
import cats.syntax.flatMap._
import cats.syntax.monadError._
import com.itv.servicebox.algebra.{Runner, _}
import com.itv.servicebox.docker
import com.itv.servicebox.interpreter._
import org.influxdb.InfluxDBFactory
import org.influxdb.dto.QueryResult
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
        serviceConfig = basePostgresConfig.copy(host = endpoints.toNel.head.host, port = endpoints.toNel.head.port)
        _ <- IOLogger.info(s"using config: $serviceConfig")
        _ <- pg.pingDb(serviceConfig)
        _ <- IOLogger.info("... connected")
      } yield ()

    object Postgres {
      val port = 5432
      val spec = Service.Spec[IO](
        "Postgres",
        NonEmptyList.of(
          Container.Spec("postgres:9.5.4",
                         Map("POSTGRES_DB"       -> basePostgresConfig.dbName,
                             "POSTGRES_PASSWORD" -> basePostgresConfig.password),
                         Set(port),
                         None,
                         Nil)),
        Service.ReadyCheck[IO](dbConnect, 10.millis, 30.seconds)
      )
    }

    object InfluxDb {
      val httpPort = 8086
      val udpPort  = 8088

      def pingAndInit(endpoints: Endpoints)(implicit timer: Timer[IO]): IO[Unit] = {
        def queryError(res: QueryResult) =
          new IllegalArgumentException(s"bad InfluxDB query: ${res.getError}")

        for {
          endpoint <- endpoints.locationFor[IO](httpPort)

          config = baseInfluxConfig.copy(host = endpoint.host, port = endpoint.port)
          client = InfluxDBFactory.connect(config.toUrl, config.user, config.password)
          q      = influx.Queries(config.dbName)

          _ <- timer.sleep(200.millis)
          _ <- IO(client.ping()).ensure(new IllegalStateException("Bad connection status"))(_.isGood)
          _ <- IO(client.query(q.dropPolicy)).ensureOr(queryError)(res => !res.hasError)
          _ <- IO(client.query(q.createPolicy)).ensureOr(queryError)(res => !res.hasError)
        } yield ()

      }

      val spec = Service.Spec[IO](
        "influxdb",
        NonEmptyList.of(
          Container.Spec(
            "influxdb:1.4.3",
            Map("INFLUXDB_DB"            -> baseInfluxConfig.dbName,
                "INFLUXDB_USER"          -> baseInfluxConfig.user,
                "INFLUXDB_USER_PASSWORD" -> baseInfluxConfig.password),
            Set(httpPort, udpPort),
            None,
            Nil
          )
        ),
        Service.ReadyCheck(pingAndInit, 5.seconds, 3.minutes)
      )
    }

    val runner = docker.runner()(Postgres.spec, InfluxDb.spec)

    (for {
      servicesByRef <- runner.setUp
      pgLocation    <- servicesByRef.locationFor(Postgres.spec.ref, Postgres.port)
      nfxLocation   <- servicesByRef.locationFor(InfluxDb.spec.ref, InfluxDb.httpPort)

    } yield {
      val dbConfig     = basePostgresConfig.copy(host = pgLocation.host, port = pgLocation.port)
      val metrixConfig = baseInfluxConfig.copy(host = nfxLocation.host, port = nfxLocation.port)
      runner -> Config(dbConfig, metrixConfig)
    }).unsafeRunSync()
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
