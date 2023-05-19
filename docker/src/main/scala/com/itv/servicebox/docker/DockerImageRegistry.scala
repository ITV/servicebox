package com.itv.servicebox.docker

import cats.MonadError
import cats.effect.kernel.Sync
import cats.syntax.functor._
import com.itv.servicebox.algebra
import com.itv.servicebox.algebra._
import com.spotify.docker.client.DockerClient
import com.spotify.docker.client.DockerClient.ListImagesParam
import com.spotify.docker.client.messages.ProgressMessage

import scala.jdk.CollectionConverters._

class DockerImageRegistry[F[_]](dockerClient: DockerClient, logger: Logger[F])(implicit S: Sync[F])
    extends algebra.ImageRegistry[F](logger) {
  override def imageExists(name: String): F[Boolean] =
    S.blocking(dockerClient.listImages(ListImagesParam.byName(name))).map(_.asScala.nonEmpty)

  override def fetchImage(name: String): F[Unit] =
    S.blocking(dockerClient.pull(name, (_: ProgressMessage) => ()))
}
