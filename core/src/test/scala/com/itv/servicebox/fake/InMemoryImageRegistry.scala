package com.itv.servicebox.fake

import cats.effect.IO
import com.itv.servicebox.algebra
import cats.syntax.functor._
import cats.syntax.monadError._

class InMemoryImageRegistry(logger: algebra.Logger[IO],
                            imagesDownloaded: Set[String] = Set.empty,
                            imagesAvaliable: Option[Set[String]] = None)
    extends algebra.ImageRegistry[IO](logger) {
  private val imagesRef = fs2.async.Ref[IO, Set[String]](imagesDownloaded).unsafeRunSync()

  def addImage(imageName: String) = imagesRef.modify(_ + imageName).void

  override def fetchImage(name: String) =
    imagesAvaliable.fold(addImage(name))(available =>
      addImage(name).ensure(new IllegalArgumentException(s"image $name is not available"))(_ => available(name)))

  override def imageExists(imageName: String): IO[Boolean] = imagesRef.get.map(_(imageName))
}
