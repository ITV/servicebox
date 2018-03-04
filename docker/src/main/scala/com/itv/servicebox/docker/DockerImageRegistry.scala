package com.itv.servicebox.docker

import cats.MonadError
import cats.syntax.functor._
import com.itv.servicebox.algebra
import com.itv.servicebox.algebra._
import com.spotify.docker.client.DockerClient
import com.spotify.docker.client.DockerClient.ListImagesParam
import com.spotify.docker.client.messages.ProgressMessage

import scala.collection.JavaConverters._

class DockerImageRegistry[F[_]](dockerClient: DockerClient, logger: Logger[F])(implicit I: ImpureEffect[F],
                                                                               M: MonadError[F, Throwable])
    extends algebra.ImageRegistry[F](logger) {
  override def imageExists(name: String): F[Boolean] =
    I.lift(dockerClient.listImages(ListImagesParam.byName(name))).map(_.asScala.nonEmpty)

  override def fetchImage(name: String): F[Unit] =
    I.lift(dockerClient.pull(name, (_: ProgressMessage) => ()))
}
