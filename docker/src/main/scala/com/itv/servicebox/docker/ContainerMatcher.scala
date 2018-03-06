package com.itv.servicebox.docker

import com.itv.servicebox.algebra
import com.itv.servicebox.algebra.Container
import Container.Matcher
import com.spotify.docker.client.messages.ContainerInfo
import scala.collection.JavaConverters._
import scala.util.Try

object ContainerMatcher extends Matcher[ContainerAndInfo] {

  override def apply(matched: ContainerAndInfo, expected: Container.Registered) = {
    val matcherResult = Matcher.Result(matched, expected)(_: Container.Registered)
    val env           = containerEnvVars(matched.info, expected.env.keySet)
    val parsed = Container
      .Registered(expected.ref, matched.container.image(), env, containerPortMappings(matched.info))

    matcherResult(parsed)
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

  private def containerPortMappings(info: ContainerInfo): Set[(Int, Int)] = {
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
          (hostPort, containerPort).tupled
      }
      .toSet
  }
}
