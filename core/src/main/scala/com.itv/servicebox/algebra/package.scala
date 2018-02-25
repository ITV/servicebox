package com.itv.servicebox

import cats.data.NonEmptyList

import scala.collection.Set
import scala.concurrent.duration.Duration

package object algebra {

  case class AppTag(value: String) extends AnyVal

  sealed trait CleanupStrategy
  object CleanupStrategy {
    case object Pause   extends CleanupStrategy
    case object Destroy extends CleanupStrategy
  }

  sealed trait Status
  object Status {
    case object Running    extends Status
    case object Paused     extends Status
    case object NotRunning extends Status
  }

  private[algebra] sealed trait Container {
    def imageName: String
    def ref(ref: Service.Ref): Container.Ref = Container.Ref(s"${ref.value}/$imageName")
    def env: Map[String, String]
  }

  object Container {
    case class Ref(value: String)
    type PortMapping = (Int, Int)

    case class Spec(imageName: String, env: Map[String, String], internalPorts: List[Int]) extends Container

    case class Registered(ref: Container.Ref,
                          imageName: String,
                          env: Map[String, String],
                          portMappings: List[Container.PortMapping],
                          status: Status)
        extends Container {
      val toSpec = Spec(imageName, env, portMappings.map(_._2))
    }
  }

  private[algebra] sealed trait Service[F[_], C <: Container] {
    def tag: AppTag
    def name: String
    def ref: Service.Ref = Service.Ref(s"${tag.value}/$name")
    def containers: NonEmptyList[C]
    def readyCheck: Service.ReadyCheck[F]
  }

  object Service {

    case class ReadyCheck[F[_]](isReady: Registry.Endpoints => F[Boolean], waitTimeout: Duration)

    case class Spec[F[_]](tag: AppTag,
                          name: String,
                          containers: NonEmptyList[Container.Spec],
                          readyCheck: ReadyCheck[F])
        extends Service[F, Container.Spec]

    case class Registered[F[_]](tag: AppTag,
                                name: String,
                                containers: NonEmptyList[Container.Registered],
                                endpoints: Registry.Endpoints,
                                readyCheck: ReadyCheck[F])
        extends Service[F, Container.Registered] {

      val status = containers.map(_.status).toList.toSet match {
        case s if s == Set(Status.Running)                                           => Status.Running
        case s if s == Set(Status.Running, Status.Paused) || s == Set(Status.Paused) => Status.Paused
        case _                                                                       => Status.NotRunning
      }

      def toSpec = Spec(tag, name, containers.map(_.toSpec), readyCheck)
    }
    case class Ref(value: String)
  }
}
