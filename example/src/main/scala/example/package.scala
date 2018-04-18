import cats.effect.IO
import cats.syntax.functor._
import doobie._
import doobie.implicits._
import cats.syntax.flatMap._
import org.flywaydb.core.Flyway

package object example {
  case class DbConfig(host: String, port: Int, dbName: String, user: String, password: String) {
    val toJdbcUrl = s"jdbc:postgresql://$host:$port/$dbName"
  }

  private def transactor(config: DbConfig): Transactor[IO] = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver", config.toJdbcUrl, config.user, config.password
  )
  private def runMigrations(config: DbConfig) = IO {
    val flyway = new Flyway()
    println(s"connecting: ${config.toJdbcUrl} with ${config.user} and ${config.password}")
    flyway.setDataSource(config.toJdbcUrl, config.user, config.password)
    flyway.migrate()
  }

  def withTransactor[A](config: DbConfig)(f: Transactor[IO] => IO[A]): IO[A] = {
    runMigrations(config) >> f(transactor(config))
  }

  def pingDb(config: DbConfig): IO[Unit] = {
    val tx = transactor(config)
    sql"select 1".query[Int].unique.transact(tx).void
  }
}
