package com.itv.servicebox.fake

import java.util.concurrent.atomic.AtomicReference
import cats.MonadError
import cats.effect.Sync
import cats.syntax.functor._
import cats.syntax.monadError._
import com.itv.servicebox.algebra

class InMemoryImageRegistry[F[_]](
    logger: algebra.Logger[F],
    imagesDownloaded: Set[String] = Set.empty,
    imagesAvaliable: Option[Set[String]] = None)(implicit S: Sync[F])
    extends algebra.ImageRegistry[F](logger) {
  private val images = new AtomicReference[Set[String]](imagesDownloaded)

  def addImage(imageName: String) = S.delay(images.getAndUpdate(_ + imageName)).void

  override def fetchImage(name: String) =
    imagesAvaliable.fold(addImage(name))(available =>
      addImage(name).ensure(new IllegalArgumentException(s"image $name is not available"))(_ => available(name)))

  override def imageExists(imageName: String): F[Boolean] = S.delay(images.get()).map(_(imageName))
}
