package com.itv.servicebox

import cats.effect.IO
import com.itv.servicebox.algebra.ImpureEffect

package object interpreter {
  implicit val ioEffect: ImpureEffect[IO] = new ImpureEffect[IO]() {
    override def lift[A](a: => A): IO[A]      = IO(a)
    override def runSync[A](effect: IO[A]): A = effect.unsafeRunSync()
  }
}
