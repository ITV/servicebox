package com.itv.servicebox.algebra

abstract class Logger[F[_]] {
  def debug(msg: String): F[Unit]
  def info(msg: String): F[Unit]
  def warn(msg: String): F[Unit]
  def error(msg: String): F[Unit]
}
