package com.itv.servicebox

import cats.data.NonEmptyList

import scala.concurrent.duration.Duration

package object algebra {

  case class AppTag(value: String) extends AnyVal

  sealed trait CleanupStrategy
  object CleanupStrategy {
    case object Pause   extends CleanupStrategy
    case object Destroy extends CleanupStrategy
  }

  private[algebra] sealed trait Container {
    def imageName: String
    def env: Map[String, String]
  }

  object Container {
    case class Id(value: String)
    type PortMapping = (Int, Int)

    case class Spec(imageName: String, env: Map[String, String], internalPorts: List[Int]) extends Container

    case class Registered(id: Container.Id,
                          imageName: String,
                          env: Map[String, String],
                          portMappings: List[Container.PortMapping])
        extends Container
  }

  private[algebra] sealed trait Service[F[_], C <: Container] {
    def tag: AppTag
    def name: String
    def id: Service.Id = Service.Id(s"${tag.value}/$name")
    def containers: NonEmptyList[C]
    def readyCheck: Service.ReadyCheck[F]
  }

  object Service {
    sealed trait Status
    object Status {
      case object Running    extends Status
      case object Paused     extends Status
      case object NotRunning extends Status
    }

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
                                status: Status,
                                readyCheck: ReadyCheck[F])
        extends Service[F, Container.Registered]
    case class Id(value: String)
  }
}
