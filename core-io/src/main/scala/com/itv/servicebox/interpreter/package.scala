package com.itv.servicebox

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{TimeUnit, TimeoutException}
import java.util.function.LongUnaryOperator

import cats.effect.{IO, Timer}
import cats.syntax.flatMap._
import com.itv.servicebox.algebra._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

package object interpreter {
  implicit val ioLogger: Logger[IO] = IOLogger

  implicit val ioEffect: ImpureEffect[IO] = new ImpureEffect[IO]() {
    override def lift[A](a: => A): IO[A]      = IO(a)
    override def runSync[A](effect: IO[A]): A = effect.unsafeRunSync()
  }

  implicit def ioScheduler(implicit logger: Logger[IO]): Scheduler[IO] = new Scheduler[IO](logger) {
    override def retry[A](f: () => IO[A], checkTimeout: FiniteDuration, totalTimeout: FiniteDuration, label: String)(
        implicit ec: ExecutionContext): IO[A] = {

      //TODO: move to signature
      val timer = implicitly[Timer[IO]]
      val timeTaken = new AtomicLong(0L)
      val incrementTimeTaken = new LongUnaryOperator() {
        override def applyAsLong(time: Long) = time + checkTimeout.toMillis
      }

      def attemptAction: IO[A] =
        for {
          elapsedSoFar <- IO(timeTaken.get())
          _ <- if (elapsedSoFar > totalTimeout.toMillis)
            IO.raiseError(new TimeoutException(s"Ready check timed out for $label after $totalTimeout"))
          else IO.unit
          _           <- logger.debug(s"running ready-check for $label [time taken so far: $elapsedSoFar ms, check timeout: ${checkTimeout.toMillis} ms, total timeout: ${totalTimeout.toMillis} ms]")
          result <- IO.race(f().attempt, timer.sleep(checkTimeout))
          _ <- IO(timeTaken.updateAndGet(incrementTimeTaken))
          outcome <- result.fold(
            _.fold(err => logger.warn(s"Ready check failed for $label: $err...") >> attemptAction, IO.pure),
            _ => logger.debug(s"ready-check attempt timed out after after $checkTimeout") >> attemptAction
          )
        } yield outcome

      attemptAction
    }
  }
}
