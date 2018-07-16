package com.itv.servicebox.algebra

import java.util.concurrent.{Executor, ExecutorService, Executors, ScheduledExecutorService}

import com.itv.servicebox.fake
import com.itv.servicebox.interpreter._
import com.itv.servicebox.test.{Dependencies, RunnerTest}

import scala.concurrent.ExecutionContext.Implicits.global
import cats.instances.future._
import com.itv.servicebox.fake.TestNetworkController

import scala.concurrent.Future

class RunnerWithFakeContainersFutureTest extends RunnerTest[Future] {
  implicit val logger: Logger[Future]             = new FutureLogger()
  val imageRegistry                               = new fake.InMemoryImageRegistry[Future](logger)
  implicit val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

  override def dependencies(implicit tag: AppTag) = {
    val networkCtrl = TestNetworkController[Future]
    new Dependencies[Future](
      logger,
      imageRegistry,
      networkCtrl,
      new fake.ContainerController[Future](imageRegistry, logger, networkCtrl.networkName),
      Scheduler.futureScheduler(executor, logger)
    )
  }
}
