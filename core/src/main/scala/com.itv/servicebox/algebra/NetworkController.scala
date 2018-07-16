package com.itv.servicebox.algebra
import cats.syntax.show._

abstract class NetworkController[F[_]] {
  def networkName: Option[NetworkName]
  def createNetwork: F[Unit]
  def removeNetwork: F[Unit]
}

object NetworkController {
  def networkName(tag: AppTag): NetworkName =
    tag.show
      .replace(":", ".")
      .replace("/", "_")
}
