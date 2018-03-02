package com.itv.servicebox

import cats.Show
import cats.data.NonEmptyList
import cats.syntax.show._

import scala.collection.Set
import scala.concurrent.duration.Duration

package object algebra {
  //TODO: consider adding an organisation/team name
  case class AppTag(org: String, appName: String)
  object AppTag {
    implicit val appTagShow: Show[AppTag] = Show.show(tag => s"${tag.org}/${tag.appName}")
  }

  sealed trait CleanupStrategy
  object CleanupStrategy {
    case object Pause   extends CleanupStrategy
    case object Destroy extends CleanupStrategy
  }

  sealed trait State
  object State {
    case object Running    extends State
    case object NotRunning extends State
  }

  private[algebra] sealed trait Container {
    def imageName: String
    def ref(ref: Service.Ref): Container.Ref = Container.Ref(s"${ref.show}/$imageName")
    def env: Map[String, String]
  }

  object Container {
    case class Ref(value: String)
    object Ref {
      implicit val refShow: Show[Ref] = Show.show(_.value)
    }
    type PortMapping = (Int, Int)

    case class Spec(imageName: String, env: Map[String, String], internalPorts: List[Int]) extends Container

    case class Registered(ref: Container.Ref,
                          imageName: String,
                          env: Map[String, String],
                          portMappings: List[Container.PortMapping],
                          state: State) //TODO: consider replacing this with a boolean or adding more granular states
        extends Container {
      val toSpec = Spec(imageName, env, portMappings.map(_._2))
    }

    trait Matcher[Repr] {
      def apply(matched: Repr, expected: Container.Registered): Matcher.Result[Repr]
    }
    object Matcher {
      sealed trait Result[Repr] {
        def matched: Repr
        def expected: Container.Registered
        def isSuccess: Boolean
      }
      object Result {
        def apply[Repr](matched: Repr, expected: Container.Registered)(actual: Container.Registered): Result[Repr] =
          if (expected == actual)
            Success(matched, expected)
          else Mismatch(matched, expected, actual)
      }

      case class Success[Repr](matched: Repr, expected: Container.Registered) extends Result[Repr] {
        override val isSuccess = true
      }

      //TODO: add some diffing here
      case class Mismatch[Repr](matched: Repr, expected: Container.Registered, actual: Container.Registered)
          extends Result[Repr] {
        override val isSuccess = false
      }
      case class Failure[Repr](matched: Repr, expected: Container.Registered, msg: String) extends Result[Repr] {
        override val isSuccess = false
      }
    }
  }

  private[algebra] sealed trait Service[F[_], C <: Container] {
    def tag: AppTag
    def name: String
    def ref: Service.Ref = Service.Ref(s"${tag.show}/$name")
    def containers: NonEmptyList[C]
    def readyCheck: Service.ReadyCheck[F]
  }

  object Service {

    case class ReadyCheck[F[_]](isReady: ServiceRegistry.Endpoints => F[Boolean], waitTimeout: Duration)

    case class Spec[F[_]](tag: AppTag,
                          name: String,
                          containers: NonEmptyList[Container.Spec],
                          readyCheck: ReadyCheck[F])
        extends Service[F, Container.Spec]

    case class Registered[F[_]](tag: AppTag,
                                name: String,
                                containers: NonEmptyList[Container.Registered],
                                endpoints: ServiceRegistry.Endpoints,
                                readyCheck: ReadyCheck[F])
        extends Service[F, Container.Registered] {

      val state = containers.map(_.state).toList.toSet match {
        case s if s == Set(State.Running) => State.Running
        case _                            => State.NotRunning
      }

      def toSpec = Spec(tag, name, containers.map(_.toSpec), readyCheck)
    }
    case class Ref(value: String)
    object Ref {
      implicit val serviceRefShow: Show[Ref] = Show.show[Ref](_.value)
    }
  }
}
