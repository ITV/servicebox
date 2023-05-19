package com.itv.servicebox.interpreter

import cats.effect.IO
import cats.effect.kernel.Clock
import com.itv.servicebox.algebra.{AppTag, Logger}
import com.itv.servicebox.fake
import com.itv.servicebox.fake.TestNetworkController
import com.itv.servicebox.test._

import scala.concurrent.ExecutionContext.Implicits.global

class RunnerWithFakeContainersIOTest(implicit clock: Clock[IO]) extends RunnerTest[IO] {
  val logger: Logger[IO] = IOLogger

  val imageRegistry = new fake.InMemoryImageRegistry[IO](logger)

  override def dependencies(implicit tag: AppTag) = {

    val networkCtrl = TestNetworkController[IO]
    new Dependencies[IO](
      logger,
      imageRegistry,
      networkCtrl,
      new fake.ContainerController[IO](imageRegistry, logger, networkCtrl.networkName) {},
      ioScheduler(logger, clock)
    )
  }
}
