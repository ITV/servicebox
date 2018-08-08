package com.itv.servicebox.docker

import cats.MonadError
import cats.effect.Effect
import cats.syntax.functor._
import com.itv.servicebox.algebra
import com.itv.servicebox.algebra._
import com.spotify.docker.client.DockerClient
import com.spotify.docker.client.DockerClient.ListImagesParam
import com.spotify.docker.client.messages.ProgressMessage

import scala.collection.JavaConverters._

class DockerImageRegistry[F[_]](dockerClient: DockerClient, logger: Logger[F])(implicit E: Effect[F],
                                                                               M: MonadError[F, Throwable])
    extends algebra.ImageRegistry[F](logger) {
  override def imageExists(name: String): F[Boolean] =
    E.delay(dockerClient.listImages(ListImagesParam.byName(name))).map(_.asScala.nonEmpty)

  override def fetchImage(name: String): F[Unit] =
    E.delay(dockerClient.pull(name, (_: ProgressMessage) => ()))
}
