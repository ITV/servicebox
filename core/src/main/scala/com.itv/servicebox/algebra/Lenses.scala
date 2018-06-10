package com.itv.servicebox.algebra

import java.nio.file.Path

import cats.data.NonEmptyList
import monocle.macros.GenLens

object Lenses {
  import monocle._
  import monocle.function.all._

  def dependsOn[F[_]]: Lens[Service.Spec[F], Set[Service.Ref]] =
    GenLens[Service.Spec[F]](_.dependencies)

  def containers[F[_]]: Lens[Service.Spec[F], NonEmptyList[Container.Spec]] =
    GenLens[Service.Spec[F]](_.containers)

  def containerAt[F[_]](idx: Int): Optional[Service.Spec[F], Container.Spec] =
    containers[F] composeOptional index(idx)

  def containerInternalPorts[F[_]]: Lens[Container.Spec, Set[Int]] =
    GenLens[Container.Spec](_.internalPorts)

  def containerEnv[F[_]]: Lens[Container.Spec, Map[String, String]] =
    GenLens[Container.Spec](_.env)

  def mounts: Lens[Container.Spec, List[BindMount]] =
    GenLens[Container.Spec](_.mounts)

  def mountFrom: Lens[BindMount, Path] = GenLens[BindMount](_.from)

  def eachContainer[F[_]]: Traversal[Service.Spec[F], Container.Spec] = containers[F] composeTraversal each

  def eachContainerEnv[F[_]]: Traversal[Service.Spec[F], Map[String, String]] =
    eachContainer[F] composeLens containerEnv
}
