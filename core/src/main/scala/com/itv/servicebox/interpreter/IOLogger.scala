package com.itv.servicebox.interpreter

import cats.effect.IO
import com.itv.servicebox.algebra
import com.typesafe.scalalogging.StrictLogging

object IOLogger extends algebra.Logger[IO] with StrictLogging {

  override def debug(msg: String): IO[Unit] = IO(logger.debug(msg))

  override def info(msg: String): IO[Unit] = IO(logger.info(msg))

  override def warn(msg: String): IO[Unit] = IO(logger.warn(msg))

  override def error(msg: String): IO[Unit] = IO(logger.error(msg))
}
