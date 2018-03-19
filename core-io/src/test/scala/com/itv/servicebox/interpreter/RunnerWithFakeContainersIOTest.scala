package com.itv.servicebox.interpreter

import java.util.concurrent.{Executors, ScheduledExecutorService}

import cats.effect.IO
import com.itv.servicebox.algebra.{AppTag, Logger, Scheduler}
import com.itv.servicebox.fake
import com.itv.servicebox.test._

class RunnerWithFakeContainersIOTest extends RunnerTest[IO] {
  implicit val logger: Logger[IO]                 = IOLogger
  implicit val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

  val imageRegistry = new fake.InMemoryImageRegistry[IO](logger)

  override def dependencies(implicit tag: AppTag) = new Dependencies[IO](
    logger,
    imageRegistry,
    new fake.ContainerController[IO](imageRegistry, logger) {},
    ioScheduler(logger)
  )
}
