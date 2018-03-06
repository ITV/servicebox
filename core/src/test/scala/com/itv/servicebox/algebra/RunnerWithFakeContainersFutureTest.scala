package com.itv.servicebox.algebra

import com.itv.servicebox.fake
import com.itv.servicebox.interpreter._
import com.itv.servicebox.test.{Dependencies, RunnerTest}
import cats.instances.future._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class RunnerWithFakeContainersFutureTest extends RunnerTest[Future] {
  val logger: Logger[Future] = new FutureLogger()
  val imageRegistry          = new fake.InMemoryImageRegistry[Future](logger)

  override def dependencies(implicit tag: AppTag) = new Dependencies[Future](
    logger,
    imageRegistry,
    new fake.ContainerController[Future](imageRegistry, logger) {}
  )
}
