package com.itv.servicebox.algebra

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ScheduledExecutorService, TimeUnit, TimeoutException}
import java.util.function.LongUnaryOperator

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
      override def retry[A](f: () => Future[A], checkTimeout: FiniteDuration, totalTimeout: FiniteDuration, label: String)(
          implicit ec: ExecutionContext): Future[A] =
        f().recoverWith {
          case NonFatal(_) =>
            val promise = Promise[A]()
            val elapsedTime = new AtomicLong(0L)
            val incrementElapsedTime = new LongUnaryOperator {
              override def applyAsLong(time: Long): Long = time + checkTimeout.toMillis
            }

            def checkUntilTotalTimeout(): Unit = {
              val msg = s"attempting to run ready check for service $label [elapsed time: ${elapsedTime.get()}, totalTimeout ms: ${totalTimeout.toMillis}]"
              (logger.debug(msg) >> f()).onComplete {
                 case scala.util.Success(a) =>
                   if (!promise.isCompleted)
                     promise.success(a)
                   else logger.warn("ready-check is running with an already completed promise. This should not happen!")
                 case scala.util.Failure(err) =>
                   logger.debug(s"ready-check failed: $err")

                   if (elapsedTime.getAndUpdate(incrementElapsedTime) < totalTimeout.toMillis) {
                     executor.schedule(new Runnable {
                       override def run() = checkUntilTotalTimeout()
                     }, checkTimeout.toMillis, TimeUnit.MILLISECONDS)
                   } else promise.failure(new TimeoutException(s"ReadyCheck $label timed out!"))
              }
            }

            checkUntilTotalTimeout()

            promise.future >>= (x =>
              logger.debug(s"""ready check completed for service: $label (promise: $promise)""").map(_ => x))
        }
    }
}
