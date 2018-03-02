package com.itv.servicebox.algebra

import cats.Monad
import cats.syntax.flatMap._

abstract class ImageRegistry[F[_]](logger: Logger[F])(implicit M: Monad[F]) {
  def fetchImage(imageName: String): F[Unit]

  def imageExists(imageName: String): F[Boolean]

  def fetchUnlessExists(imageName: String): F[Unit] =
    imageExists(imageName).flatMap(exists => if (exists) M.unit else fetchImage(imageName))
}
