package com.itv.servicebox.docker

import com.itv.servicebox.algebra
import com.itv.servicebox.algebra.Container
import Container.Matcher
import com.spotify.docker.client.messages.{Container => JavaContainer, _}
import scala.collection.JavaConverters._
import scala.util.Try

object ContainerMapper extends Matcher[ContainerAndInfo] {

  override def apply(matched: ContainerAndInfo, expected: Container.Registered) = {
    val failure = Matcher.Failure(matched, expected, _: String)

    val status: Option[algebra.Status] = matched.container.status() match {
      case "paused"  => Some(algebra.Status.Paused)
      case "running" => Some(algebra.Status.Running)
      case _         => None

    }

    status
      .fold[Matcher.Result[ContainerAndInfo]](failure(s"Unexpected container status: ${matched.container.status()}")) {
        status =>
          val env = containerEnvVars(matched.info, expected.env.keySet)
          Matcher.Success(matched,
                          Container.Registered(expected.ref,
                                               matched.container.image(),
                                               env,
                                               containerPortMappings(matched.info),
                                               status))
      }
  }

  private def containerEnvVars(info: ContainerInfo, envVarsWhitelist: Set[String]): Map[String, String] =
    info
      .config()
      .env()
      .asScala
      .flatMap {
        _.split("=").toList match {
          case k :: Nil =>
            Some(k -> "")
          case k :: v :: _ =>
            Some(k -> v)
          case _ => None
        }
      }
      .filter { case (k, _) => envVarsWhitelist(k) }
      .toMap

  private def containerPortMappings(info: ContainerInfo): List[(Int, Int)] = {
    import cats.instances.option._
    import cats.syntax.apply._

    info
      .networkSettings()
      .ports()
      .asScala
      .flatMap {
        case (port, binding) =>
          val containerPort = Try {
            port.takeWhile(_.isDigit).toInt
          }.toOption
          val hostPort = binding.asScala.headOption.map(_.hostPort().toInt)
          (containerPort, hostPort).tupled
      }
      .toList
  }
}
