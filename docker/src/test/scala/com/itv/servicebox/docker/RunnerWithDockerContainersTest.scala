package com.itv.servicebox.docker

import cats.effect.IO
import com.itv.servicebox.interpreter.IOLogger
import com.itv.servicebox.test
import com.itv.servicebox.test.RunnerTest
import com.spotify.docker.client.DefaultDockerClient

class RunnerWithDockerContainersTest extends RunnerTest {
  override val dependencies: test.Dependencies[IO] = {
    val dockerClient  = DefaultDockerClient.fromEnv.build
    val imageRegistry = new DockerImageRegistry[IO](dockerClient, IOLogger)
    val containerController =
      new DockerContainerController[IO](dockerClient, imageRegistry, IOLogger)
    test.Dependencies(imageRegistry, containerController)
  }
}
