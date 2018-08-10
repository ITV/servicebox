package com.itv.servicebox.docker

import java.nio.file.Paths

import cats.data.NonEmptyList
import com.itv.servicebox.algebra.Container.Matcher
import com.itv.servicebox.algebra.{BindMount, Container}
import com.spotify.docker.client.messages.ContainerInfo

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.Try

object ContainerMatcher extends Matcher[ContainerWithDetails] {

  override def apply(matched: ContainerWithDetails,
                     expected: Container.Registered): Matcher.Result[ContainerWithDetails] = {
    val matcherResult = Matcher.Result(matched, expected)(_: Container.Registered)
    val env           = envVars(matched.info, expected.env.keySet)

    val parsed = Container.Registered(
      expected.ref,
      matched.container.image(),
      env,
      containerPortMappings(matched.info),
      maybeCmd(matched, expected),
      bindMounts(matched.info),
      expected.name.flatMap(assignedContainerName(matched, _))
    )

    matcherResult(parsed)
  }

  private def assignedContainerName(matched: ContainerWithDetails, expectedName: String) =
    Option(matched.info.name.replaceFirst("^\\/", "")).filter(_ =>
      matched.container.labels().asScala.exists(_ == AssignedName -> expectedName))

  private def maybeCmd(matched: ContainerWithDetails, expected: Container.Registered) = {
    val entrypoint = matched.info.config.entrypoint.asScala.toList

    @tailrec
    def removeEntrypoint(acc: List[String])(cmd: List[String], ep: List[String]): List[String] = (cmd, ep) match {
      case (Nil, _) => acc
      case (_, Nil) => acc ++ cmd
      case (c :: ctail, e :: etail) =>
        if (c == e) removeEntrypoint(acc)(ctail, etail)
        else removeEntrypoint(acc :+ c)(ctail, etail)
    }

    if (expected.command.isEmpty)
      None
    else
      Option(matched.container.command).flatMap(cmd =>
        NonEmptyList.fromList(removeEntrypoint(Nil)(cmd.split(' ').toList, entrypoint)))
  }

  private def bindMounts(info: ContainerInfo) =
    NonEmptyList.fromList(info.mounts().asScala.toList.filter(_.`type` == "bind").map { bind =>
      BindMount(Paths.get(bind.source), Paths.get(bind.destination), !bind.rw)
    })

  private def envVars(info: ContainerInfo, envVarsWhitelist: Set[String]): Map[String, String] =
    info.config.env.asScala
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

    info.networkSettings.ports.asScala.flatMap {
      case (port, binding) =>
        val containerPort = Try {
          port.takeWhile(_.isDigit).toInt
        }.toOption
        val hostPort = binding.asScala.headOption.map(_.hostPort().toInt)
        (hostPort, containerPort).tupled
    }.toSet
  }
}
