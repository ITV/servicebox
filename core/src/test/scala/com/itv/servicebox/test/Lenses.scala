package com.itv.servicebox.test

import cats.Applicative
import monocle.{Lens, Optional}
import monocle.function.all._
import monocle.macros.GenLens

import com.itv.servicebox.algebra.Service

object Lenses {
  def portRange[F[_]](implicit ap: Applicative[F]): Lens[TestData[F], Range] =
    GenLens[TestData[F]](_.portRange)

  def preExisting[F[_]](implicit ap: Applicative[F]): Lens[TestData[F], List[RunningContainer]] =
    GenLens[TestData[F]](_.preExisting)

  def services[F[_]](implicit ap: Applicative[F]): Lens[TestData[F], Map[Service.Ref, Service.Spec[F]]] =
    GenLens[TestData[F]](_.servicesByRef)

  def readyCheck[F[_]](implicit ap: Applicative[F]): Lens[Service.Spec[F], Service.ReadyCheck[F]] =
    GenLens[Service.Spec[F]](_.readyCheck)

  def serviceAt[F[_]](idx: Service.Ref)(implicit ap: Applicative[F]): Optional[TestData[F], Service.Spec[F]] =
    services composeOptional index(idx)

}
