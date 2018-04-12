import cats.effect.IO
import com.itv.servicebox.algebra._
import com.itv.servicebox.interpreter._
import com.itv.servicebox.docker
import scala.concurrent.duration._
import ServiceRegistry.Endpoints
import cats.data.NonEmptyList
import com.typesafe.scalalogging.StrictLogging

object SimpleRunner extends App with StrictLogging {
  scala.concurrent.Future.successful()

  object Postgres {
    case class DbConfig(host: String, dbName: String, password: String, port: Int)

    def pingDb(value: DbConfig): IO[Unit] = ???

    def apply(config: DbConfig): Service.Spec[IO] = {

      def dbConnect(endpoints: Endpoints): IO[Unit] =
        for {
          _ <- IO(logger.info("Attempting to connect to DB"))
          _ <- pingDb(config.copy(host = endpoints.head.host, port = endpoints.head.port))
        } yield ()

      Service.Spec[IO](
        "Postgres",
        NonEmptyList.of(
          Container.Spec("postgres:9.5.4",
                         Map("POSTGRES_DB" -> config.dbName, "POSTGRES_PASSWORD" -> config.password),
                         Set(5432))),
        Service.ReadyCheck[IO](dbConnect, 50.millis, 1.minute)
      )
    }
  }
}
