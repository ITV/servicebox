package com.itv.servicebox

import cats.{Monad, MonadError}
import cats.effect.IO
import com.spotify.docker.client.messages.{ContainerInfo, Container => JavaContainer}

import scala.concurrent.{ExecutionContext, Future}

package object docker {
  case class ContainerAndInfo(container: JavaContainer, info: ContainerInfo)

  //pseudo typeclass to lift a value into a potentially impure effect
  //into a monad

  abstract class ImpureEffect[F[_]](implicit M: MonadError[F, Throwable]) {
    def lift[A](a: A): F[A]
  }

  object ImpureEffect {
    implicit def futureImpure(implicit ec: ExecutionContext, M: MonadError[Future, Throwable]) =
      new ImpureEffect[Future]() {
        override def lift[A](a: A) = Future(a)
      }
    implicit val ioImpure = new ImpureEffect[IO] {
      override def lift[A](a: A) = IO.pure(a)
    }
  }

}
