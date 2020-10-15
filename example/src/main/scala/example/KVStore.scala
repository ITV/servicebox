package example

import java.util.concurrent.TimeUnit

import cats.effect.{ContextShift, IO}
import cats.syntax.functor._
import cats.syntax.apply._
import doobie._
import doobie.implicits._
import org.influxdb.InfluxDBFactory
import org.influxdb.dto.Point

trait KVStore {
  def set(key: String, value: String)(implicit cs: ContextShift[IO]): IO[Unit]
  def get(key: String)(implicit cs: ContextShift[IO]): IO[Option[String]]
}

object KVStore {
  def apply(config: Config): KVStore = new KVStore {
    implicit val lh: LogHandler = LogHandler.jdkLogHandler

    val influxDb = InfluxDBFactory.connect(config.metricsDb.toUrl, config.metricsDb.user, config.metricsDb.password)

    override def set(key: String, value: String)(implicit cs: ContextShift[IO]): IO[Unit] =
      pg.withTransactor(config.db) { tx =>
        def writeMetric =
          for {
            now <- IO(System.currentTimeMillis())
            q = influx.Queries(config.metricsDb.dbName)
            _ <- IO(
              influxDb.write(q.dbName,
                             q.policyName,
                             Point
                               .measurement("key.set")
                               .time(now, TimeUnit.MILLISECONDS)
                               .addField("key", key)
                               .addField("value", value)
                               .build()))
          } yield ()

        val q =
          sql"""
             |INSERT INTO keyvalues
             |VALUES ($key, $value)
             |ON CONFLICT (key)
             |DO UPDATE SET value = $value
           """.stripMargin
        q.updateWithLogHandler(lh).run.transact(tx).void *> writeMetric
      }

    override def get(key: String)(implicit cs: ContextShift[IO]): IO[Option[String]] =
      pg.withTransactor(config.db) { tx =>
        val q = fr"select value from keyvalues " ++ Fragments.whereAnd(fr"key = $key")
        q.queryWithLogHandler[String](lh).option.transact(tx)
      }
  }
}
