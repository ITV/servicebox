package com.itv.servicebox.interpreter

import com.itv.servicebox.algebra
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.{ExecutionContext, Future}

class FutureLogger(implicit ec: ExecutionContext) extends algebra.Logger[Future] with StrictLogging {
  override def debug(msg: String) = Future(logger.debug(msg))

  override def info(msg: String) = Future(logger.info(msg))

  override def warn(msg: String) = Future(logger.warn(msg))

  override def error(msg: String) = Future(logger.error(msg))
}
