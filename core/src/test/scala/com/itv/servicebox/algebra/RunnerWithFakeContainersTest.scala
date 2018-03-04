package com.itv.servicebox.algebra

import cats.effect.IO
import com.itv.servicebox.test
import com.itv.servicebox.fake
import com.itv.servicebox.interpreter.IOLogger
import com.itv.servicebox.test.{Dependencies, RunnerTest}

class RunnerWithFakeContainersTest extends RunnerTest {
  override val dependencies: test.Dependencies[IO] = {
    val imageRegistry = new fake.InMemoryImageRegistry(IOLogger)
    val containerCtrl = new fake.ContainerController(imageRegistry, IOLogger)
    Dependencies(imageRegistry, containerCtrl)
  }
}
