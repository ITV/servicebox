package com.itv.servicebox.test

import cats.Applicative
import monocle.{Lens, Optional}
import monocle.function.all._
import monocle.macros.GenLens

import com.itv.servicebox.algebra.Service

object Lenses {
  def portRange[F[_]: Applicative]: Lens[TestData[F], Range] =
    GenLens[TestData[F]](_.portRange)

  def preExisting[F[_]: Applicative]: Lens[TestData[F], List[RunningContainer]] =
    GenLens[TestData[F]](_.preExisting)

  def services[F[_]: Applicative]: Lens[TestData[F], Map[Service.Ref, Service.Spec[F]]] =
    GenLens[TestData[F]](_.servicesByRef)

  def readyCheck[F[_]: Applicative]: Lens[Service.Spec[F], Service.ReadyCheck[F]] =
    GenLens[Service.Spec[F]](_.readyCheck)

  def serviceAt[F[_]: Applicative](idx: Service.Ref): Optional[TestData[F], Service.Spec[F]] =
    services composeOptional index(idx)

}
