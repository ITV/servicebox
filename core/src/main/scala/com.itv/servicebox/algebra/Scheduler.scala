package com.itv.servicebox.algebra

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

abstract class Scheduler[F[_]](logger: Logger[F]) {
  def retry[A](f: () => F[A], interval: FiniteDuration, timeout: FiniteDuration, label: String)(
      implicit ec: ExecutionContext): F[A]
}
