package com.itv.servicebox

import cats.Show
import cats.data.NonEmptyList
import cats.syntax.either._
import cats.syntax.foldable._
import cats.syntax.show._
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

package object algebra {
  type PortMapping = (Int, Int)

  type ContainerMappings = Map[Container.Ref, Set[PortMapping]]

  //TODO: consider adding an organisation/team name
  case class AppTag(org: String, appName: String)
  object AppTag {
    implicit val appTagShow: Show[AppTag] = Show.show(tag => s"${tag.org}/${tag.appName}")
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

    case class Spec(imageName: String,
                    env: Map[String, String],
                    internalPorts: Set[Int],
                    command: Option[NonEmptyList[String]])
        extends Container

    case class Registered(ref: Container.Ref,
                          imageName: String,
                          env: Map[String, String],
                          portMappings: Set[PortMapping],
                          command: Option[NonEmptyList[String]])
        extends Container {
      lazy val toSpec = Spec(imageName, env, portMappings.map(_._2), command)
    }

    trait Matcher[Repr] {
      def apply(matched: Repr, expected: Container.Registered): Matcher.Result[Repr]
    }
    object Matcher {
      sealed trait Result[Repr] {
        def matched: Repr
        def expected: Container.Registered
        def actual: Container.Registered
        def isSuccess: Boolean
      }
      object Result {
        def apply[Repr](matched: Repr, expected: Container.Registered)(actual: Container.Registered): Result[Repr] =
          if (expected.toSpec == actual.toSpec)
            Success(matched, expected, actual)
          else Mismatch(matched, expected, actual)
      }

      case class Success[Repr](matched: Repr, expected: Container.Registered, actual: Container.Registered)
          extends Result[Repr] {
        override val isSuccess = true
      }

      //TODO: consider adding some diffing here
      case class Mismatch[Repr](matched: Repr, expected: Container.Registered, actual: Container.Registered)
          extends Result[Repr] {
        override val isSuccess = false
      }
    }
  }

  private[algebra] sealed trait Service[F[_], C <: Container] {
    def name: String
    def ref(implicit tag: AppTag): Service.Ref = Service.Ref(s"${tag.show}/$name")
    def containers: NonEmptyList[C]
    def readyCheck: Service.ReadyCheck[F]
  }

  object Service {

    case class ReadyCheck[F[_]](isReady: Endpoints => F[Unit],
                                attemptTimeout: FiniteDuration,
                                totalTimeout: FiniteDuration,
                                label: Option[String] = None)

    case class RuntimeInfo(setupDuration: FiniteDuration, readyCheckDuration: FiniteDuration)

    case class Spec[F[_]](name: String, containers: NonEmptyList[Container.Spec], readyCheck: ReadyCheck[F])
        extends Service[F, Container.Spec] {
      def register(mappings: ContainerMappings)(implicit tag: AppTag): Either[Throwable, Registered[F]] = {
        val containerList = containers.toList
        val diff          = containerList.map(_.ref(this.ref)).toSet diff mappings.keySet
        val registeredContainers = containerList.flatMap { c =>
          mappings
            .get(c.ref(ref))
            .map(ms => Container.Registered(c.ref(ref), c.imageName, c.env, ms, c.command))
        }

        registeredContainers
          .asRight[Throwable]
          .ensure(new IllegalArgumentException(
            s"Mappings not supplied for for containers: ${diff.mkString(",")}. Mappings supplied: ${mappings}"))(_ =>
            diff.isEmpty)
          .map { cs =>
            val locations =
              cs.flatMap(_.portMappings).map((Location.localhost _).tupled)
            Registered(name,
                       NonEmptyList.fromListUnsafe(cs),
                       Endpoints(NonEmptyList.fromListUnsafe(locations)),
                       readyCheck)
          }
      }
    }

    case class Registered[F[_]](name: String,
                                containers: NonEmptyList[Container.Registered],
                                endpoints: Endpoints,
                                readyCheck: ReadyCheck[F])
        extends Service[F, Container.Registered] {

      def toSpec = Spec(name, containers.map(_.toSpec), readyCheck)
    }
    case class Ref(value: String)
    object Ref {
      implicit val serviceRefShow: Show[Ref] = Show.show[Ref](_.value)
    }
  }

  case class Location(host: String, port: Int, containerPort: Int)
  object Location {
    def localhost(port: Int, containerPort: Int): Location = Location("127.0.0.1", port, containerPort)
  }

  case class Endpoints(toNel: NonEmptyList[Location]) {
    val toList: List[Location] = toNel.toList

    def locationFor(containerPort: Int): Either[Throwable, Location] = {
      val portMappings = toNel.map(l => s"${l.port} -> ${l.containerPort}").toList.mkString(", ")
      toNel
        .find(_.containerPort == containerPort)
        .toRight(new IllegalArgumentException(
          s"Cannot find a location for $containerPort. Existing port mappings: $portMappings"))
    }

    def unsafeLocationFor(containerPort: Int): Location =
      locationFor(containerPort).valueOr(throw _)
  }

  object ServicesByRef {
    def empty[F[_]]: ServicesByRef[F] = ServicesByRef[F](Map.empty)
  }
  case class ServicesByRef[F[_]](toMap: Map[Service.Ref, Service.Registered[F]]) {
    def toList: List[Service.Registered[F]] = toMap.values.toList
    def +(registered: Service.Registered[F])(implicit tag: AppTag): ServicesByRef[F] =
      copy(toMap = toMap + (registered.ref -> registered))

    def locationFor(ref: Service.Ref, containerPort: Int): Either[Throwable, Location] =
      for {
        service <- toMap
          .get(ref)
          .toRight(new IllegalArgumentException(
            s"Cannot find a service with ref: ${ref.show}. Registered services: ${toMap.keys.map(_.show).mkString(", ")}"))
        location <- service.endpoints.locationFor(containerPort)

      } yield location

    def unsafeLocationFor(ref: Service.Ref, containerPort: Int): Location =
      locationFor(ref, containerPort).valueOr(throw _)
  }

  //lawless typeclass to lift a value from a potentially impure effect
  //into a monad error
  abstract class ImpureEffect[F[_]] {
    def lift[A](a: => A): F[A]
    def runSync[A](effect: F[A]): A
  }

  object ImpureEffect {
    implicit def futureImpure(implicit ec: ExecutionContext) =
      new ImpureEffect[Future]() {
        override def lift[A](a: => A)             = Future(a)
        override def runSync[A](fa: Future[A]): A = Await.result(fa, Duration.Inf)
      }
  }
}
