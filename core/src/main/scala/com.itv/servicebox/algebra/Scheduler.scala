package com.itv.servicebox.algebra

import java.util.concurrent.{ScheduledExecutorService, TimeUnit, TimeoutException}

import cats.instances.future._
import cats.syntax.flatMap._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

abstract class Scheduler[F[_]](logger: Logger[F]) {
  def retry[A](f: () => F[A], interval: FiniteDuration, timeout: FiniteDuration, label: String)(
      implicit ec: ExecutionContext): F[A]
}

object Scheduler {
  implicit def futureScheduler(implicit executor: ScheduledExecutorService, logger: Logger[Future]): Scheduler[Future] =
    new Scheduler[Future](logger) {
      override def retry[A](f: () => Future[A], interval: FiniteDuration, timeout: FiniteDuration, label: String)(
          implicit ec: ExecutionContext): Future[A] =
        f().recoverWith {
          case NonFatal(_) =>
            val promise = Promise[A]()

            val backgroundTask = executor.scheduleAtFixedRate(
              new Runnable {
                override def run() =
                  (logger.debug(s"attempting to run ready check for service $label") >> f()).onComplete {
                    case scala.util.Success(a) =>
                      if (!promise.isCompleted)
                        promise.success(a)
                    case scala.util.Failure(err) =>
                      ()
                      logger.warn(s"promise already fulfilled: $err") //TODO: log something
                  }
              },
              0L,
              interval.toMillis,
              TimeUnit.MILLISECONDS
            )

            executor.schedule(
              new Runnable {
                override def run() = {
                  backgroundTask.cancel(true)
                  promise.failure(new TimeoutException(s"ReadyCheck $label timed out!"))
                }
              },
              timeout.toMillis,
              TimeUnit.MILLISECONDS
            )

            promise.future >>= (x =>
              logger.debug(s"""ready check completed for service: $label (promise: $promise)""").map(_ => x))
        }
    }
}
