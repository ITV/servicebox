package com.itv.servicebox.interpreter

import cats.effect.IO
import cats.syntax.functor._
import com.itv.servicebox.algebra
import com.itv.servicebox.algebra._

class IOServiceController(registry: ServiceRegistry[IO], containerCtrl: ContainerController[IO])
    extends algebra.ServiceController[IO](IOLogger, registry, containerCtrl) {

  override def waitUntilReady(service: Service.Registered[IO]) =
    service.readyCheck.isReady(service.endpoints).void
}
