import cats.effect.IO
import cats.syntax.apply._
import cats.syntax.functor._
import doobie._
import doobie.implicits._
import org.flywaydb.core.Flyway
import org.influxdb.dto

package object example {
  case class DbConfig(host: String, port: Int, dbName: String, user: String, password: String) {
    val toJdbcUrl = s"jdbc:postgresql://$host:$port/$dbName"
  }

  case class InfluxDBConfig(host: String, port: Int, dbName: String, user: String, password: String) {
    val toUrl = s"http://$host:$port"
  }

  case class Config(db: DbConfig, metricsDb: InfluxDBConfig)

  object pg {
    private def transactor(config: DbConfig): Transactor[IO] = Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      config.toJdbcUrl,
      config.user,
      config.password
    )

    private def runMigrations(config: DbConfig) = IO {
      val flyway = new Flyway()
      flyway.setDataSource(config.toJdbcUrl, config.user, config.password)
      flyway.migrate()
    }

    def withTransactor[A](config: DbConfig)(f: Transactor[IO] => IO[A]): IO[A] =
      runMigrations(config) *> f(transactor(config))

    def pingDb(config: DbConfig): IO[Unit] = {
      val tx = transactor(config)
      sql"select 1".query[Int].unique.transact(tx).void
    }
  }

  object influx {
    case class Queries(dbName: String) {
      val policyName = "kvstore_default"
      val dropPolicy = new dto.Query(s"""DROP RETENTION POLICY "$policyName" ON "$dbName" """, dbName)
      val createPolicy = new dto.Query(
        s"""CREATE RETENTION POLICY "$policyName" ON "$dbName" DURATION 30d REPLICATION 1 DEFAULT""",
        dbName
      )
    }

  }
}
