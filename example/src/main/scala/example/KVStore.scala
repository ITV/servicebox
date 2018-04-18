package example

import cats.effect.IO
import cats.syntax.functor._
import doobie._
import doobie.implicits._

trait KVStore {
  def set(key: String, value: String): IO[Unit]
  def get(key: String): IO[Option[String]]
}

object KVStore {
  def apply(dbConfig: DbConfig): KVStore = new KVStore {
    implicit val lh: LogHandler = LogHandler.jdkLogHandler

    override def set(key: String, value: String): IO[Unit] = withTransactor(dbConfig) { tx =>
      val q =
        sql"""
             |INSERT INTO keyvalues
             |VALUES ($key, $value)
             |ON CONFLICT (key)
             |DO UPDATE SET value = $value
           """.stripMargin
      q.updateWithLogHandler(lh).run.transact(tx).void
    }

    override def get(key: String): IO[Option[String]] = {
      withTransactor(dbConfig) { tx =>
        val q = fr"select value from keyvalues " ++ Fragments.whereAnd(fr"key = $key")
        q.queryWithLogHandler[String](lh).option.transact(tx)
      }
    }
  }
}
