package com.itv.servicebox

import java.util.concurrent.{TimeUnit, TimeoutException}

import cats.effect.{IO, Timer}
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
      import cats.syntax.flatMap._

      def currentTimeMs = timer.clockMonotonic(TimeUnit.MILLISECONDS).map(FiniteDuration(_, TimeUnit.MILLISECONDS))
      def lapseTime(startTime: FiniteDuration) = currentTimeMs.map(_ - startTime)

      def attemptAction(startTime: FiniteDuration): IO[A] =
        for {
          elapsed <- lapseTime(startTime)
          _ <- if (elapsed > totalTimeout) {
            logger.warn("exiting loop")
            IO.raiseError(new TimeoutException(s"Ready check timed out for $label after $totalTimeout"))
          }
          else IO.unit
          _           <- logger.debug(s"running ready-check for $label [time taken so far: $elapsed ms, check timeout: ${checkTimeout.toMillis} ms, total timeout: ${totalTimeout.toMillis} ms]")
          result <- IO.race(f().attempt, IO(Thread.sleep(checkTimeout.toMillis)))
          outcome <- result.fold(
            _.fold(err => logger.warn(s"Ready check failed for $label: $err...") >> attemptAction(startTime), IO.pure),
            _ => logger.debug(s"ready-check attempt timed out after after $checkTimeout") >> attemptAction(startTime)
          )
        } yield outcome

      currentTimeMs flatMap attemptAction
    }
  }
}
