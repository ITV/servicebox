package com.itv.servicebox

import java.util.concurrent.{TimeUnit, TimeoutException}

import cats.effect.{ContextShift, IO, Timer}
import cats.syntax.apply._
import scala.concurrent.duration._
import com.itv.servicebox.algebra._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

package object interpreter {
  implicit val ioLogger: Logger[IO] = IOLogger

  implicit def ioScheduler(implicit logger: Logger[IO]): Scheduler[IO] = new Scheduler[IO](logger) {
    override def retry[A](f: () => IO[A], checkTimeout: FiniteDuration, totalTimeout: FiniteDuration, label: String)(implicit ec: ExecutionContext): IO[A] = {

      implicit val timer: Timer[IO] = IO.timer(ec)
      implicit val cs: ContextShift[IO] = IO.contextShift(ec)

      def currentTimeMs = timer.clock.monotonic(TimeUnit.MILLISECONDS).map(FiniteDuration(_, TimeUnit.MILLISECONDS))

      def lapseTime(startTime: FiniteDuration) = currentTimeMs.map(_ - startTime)

      def attemptAction(startTime: FiniteDuration): IO[A] =
        for {
          elapsed <- lapseTime(startTime)
          _ <- if (elapsed > totalTimeout) {
            IO(logger.warn(s"exiting loop. Elapsed time: $elapsed")) *>
              IO.raiseError(new TimeoutException(s"Ready check timed out for $label after $totalTimeout"))
          } else IO.unit
          attemptBegin <- currentTimeMs
          _ <- logger.debug(
            s"running ready-check for $label [time taken so far: $elapsed, check timeout: $checkTimeout, total timeout: $totalTimeout]")
          result <- f().timeout(checkTimeout).attempt
          sleepRemainder <- lapseTime(attemptBegin).map(elapsedTime => List(checkTimeout - elapsedTime, 0.millis).max)
          outcome <- result.fold(
            err => logger.warn(s"Ready check failed for $label: $err...") *> IO.sleep(sleepRemainder) *> attemptAction(startTime),
            out => IO(logger.debug(s"done! total elapsed time: $elapsed")) *> IO.pure(out)
          )
        } yield outcome

      currentTimeMs flatMap attemptAction
    }
  }
}
