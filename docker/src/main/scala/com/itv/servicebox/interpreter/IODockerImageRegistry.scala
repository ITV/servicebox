package com.itv.servicebox.interpreter

import cats.effect.IO
import com.itv.servicebox.algebra
import algebra._
import com.spotify.docker.client.DockerClient
import com.spotify.docker.client.DockerClient.ListImagesParam
import com.spotify.docker.client.messages.ProgressMessage

import scala.collection.JavaConverters._

class IODockerImageRegistry(dockerClient: DockerClient, logger: Logger[IO]) extends algebra.ImageRegistry[IO](logger) {

  override def imageExists(name: String): IO[Boolean] =
    IO(dockerClient.listImages(ListImagesParam.byName(name))).map(_.asScala.nonEmpty)

  override def fetchImage(name: String): IO[Unit] =
    IO(dockerClient.pull(name, (_: ProgressMessage) => ()))
}
