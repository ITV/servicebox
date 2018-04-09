package com.itv.servicebox.test

import cats.Applicative
import monocle.{Lens, Optional}
import monocle.function.all._
import monocle.macros.GenLens

import com.itv.servicebox.algebra.Service

object Lenses {
  def portRange[F[_]: Applicative]: Lens[TestData[F], Range] =
    GenLens[TestData[F]](_.portRange)

  def services[F[_]: Applicative]: Lens[TestData[F], List[Service.Spec[F]]] =
    GenLens[TestData[F]](_.services)

  def readyCheck[F[_]: Applicative]: Lens[Service.Spec[F], Service.ReadyCheck[F]] =
    GenLens[Service.Spec[F]](_.readyCheck)

  def serviceAt[F[_]: Applicative](idx: Int): Optional[TestData[F], Service.Spec[F]] =
    services.composeOptional(index(idx))
}
