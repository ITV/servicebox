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

  def containerEnv[F[_]]: Lens[Container.Spec, Map[String, String]] =
    GenLens[Container.Spec](_.env)

  def mounts: Optional[Container.Spec, NonEmptyList[BindMount]] =
    Optional[Container.Spec, NonEmptyList[BindMount]](_.mounts)(ms => s => s.copy(mounts = Some(ms)))

  def name[F[_]]: Optional[Container.Spec, String] =
    Optional[Container.Spec, String](_.name)(n => s => s.copy(name = Some(n)))

  def mountFrom: Lens[BindMount, Path] = GenLens[BindMount](_.from)

  def containerPorts: Lens[Container.Spec, List[PortSpec]] = GenLens[Container.Spec](_.ports)

  def eachContainerPort[F[_]]: Traversal[Service.Spec[F], PortSpec] =
    eachContainer[F].composeTraversal(containerPorts composeTraversal each)

  def eachContainer[F[_]]: Traversal[Service.Spec[F], Container.Spec] = containers[F] composeTraversal each

  def eachContainerEnv[F[_]]: Traversal[Service.Spec[F], Map[String, String]] =
    eachContainer[F] composeLens containerEnv
}
