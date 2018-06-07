package com.itv.servicebox

import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import cats.data.{NonEmptyList, StateT}
import cats.instances.all._
import cats.syntax.all._
import cats.{ApplicativeError, Monad, Show}
import monocle.function.all._

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

package object algebra {
  private val L = Lenses

  type PortMapping = (Int, Int)

  type ContainerMappings = Map[Container.Ref, Set[PortMapping]]

  type PortAllocation[F[_], A] = StateT[F, AtomicReference[Range], A]

  case class AppTag(org: String, appName: String)
  object AppTag {
    implicit val appTagShow: Show[AppTag] = Show.show(tag => s"${tag.org}/${tag.appName}")
  }

  case class BindMount(from: Path, to: Path, readOnly: Boolean = false)

  object BindMount {
    def fromTmpFileContent[F[_]](baseDir: Path)(to: Path, ro: Boolean = false)(
        files: (String, Array[Byte])*)(implicit I: ImpureEffect[F], M: Monad[F]): F[BindMount] = {

      val mountDir = baseDir.resolve(UUID.randomUUID().toString)

      for {

        _ <- I
          .lift(baseDir.toFile.exists())
          .ifM(I.lift(()), I.lift(Files.createDirectory(baseDir)).void)

        _ <- I.lift(Files.createDirectory(mountDir))

        _ <- files.toList.traverse_ {
          case (fileName, content) =>
            I.lift(Files.write(mountDir.resolve(fileName), content))
        }
      } yield BindMount(mountDir, to, ro)
    }
  }

  private[algebra] sealed trait Container {
    def imageName: String
    def ref(ref: Service.Ref): Container.Ref = Container.Ref(s"${ref.show}/$imageName")
    def env: Map[String, String]
    def command: Option[NonEmptyList[String]]
    def mounts: List[BindMount]
  }

  object Container {
    case class Ref(value: String)
    object Ref {
      implicit val refShow: Show[Ref] = Show.show(_.value)
    }

    case class Spec(imageName: String,
                    env: Map[String, String],
                    internalPorts: Set[Int],
                    command: Option[NonEmptyList[String]],
                    mounts: List[BindMount])
        extends Container {

      def withAbsolutePaths: Spec =
        (L.mounts composeTraversal each composeLens L.mountFrom)
          .modify(_.toAbsolutePath)(this)

      def register(hostPortRange: Seq[Int], serviceRef: Service.Ref): Either[Throwable, Container.Registered] =
        if (hostPortRange.size < internalPorts.size)
          Left(
            new IllegalArgumentException(
              s"supplied port range is too small! $hostPortRange, internal ports: $internalPorts"))
        else
          Right(
            Registered(
              ref(serviceRef),
              imageName,
              env,
              internalPorts.zip(hostPortRange).map(_.swap),
              command,
              mounts
            ))
    }

    case class Registered(ref: Container.Ref,
                          imageName: String,
                          env: Map[String, String],
                          portMappings: Set[PortMapping],
                          command: Option[NonEmptyList[String]],
                          mounts: List[BindMount])
        extends Container {
      lazy val toSpec = Spec(imageName, env, portMappings.map(_._2), command, mounts).withAbsolutePaths
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
    def dependencies: Set[Service.Ref]
  }

  object Service {

    case class ReadyCheck[F[_]](isReady: Endpoints => F[Unit],
                                attemptTimeout: FiniteDuration,
                                totalTimeout: FiniteDuration,
                                label: Option[String] = None)

    case class RuntimeInfo(setupDuration: FiniteDuration, readyCheckDuration: FiniteDuration)

    case class Spec[F[_]](name: String,
                          containers: NonEmptyList[Container.Spec],
                          readyCheck: ReadyCheck[F],
                          dependencies: Set[Service.Ref] = Set.empty)
        extends Service[F, Container.Spec] {

      def mapContainers(f: Container.Spec => Container.Spec): Spec[F] = copy(containers = containers.map(f))

      def dependsOn(ref: Service.Ref): Service.Spec[F] =
        L.dependsOn.modify(_ + ref)(this)

      def mergeToContainersEnv(extra: Map[String, String]): Service.Spec[F] =
        L.eachContainerEnv.modify(_ ++ extra)(this)

      def register(mappings: ContainerMappings)(implicit tag: AppTag): Either[Throwable, Service.Registered[F]] = {
        val containerList = containers.toList
        val diff          = containerList.map(_.ref(ref)).toSet diff mappings.keySet
        val registeredContainers = containerList.flatMap { c =>
          mappings
            .get(c.ref(ref))
            .map(ms => Container.Registered(c.ref(ref), c.imageName, c.env, ms, c.command, c.mounts))
        }

        registeredContainers
          .asRight[Throwable]
          .ensure(new IllegalArgumentException(
            s"Mappings not supplied for for containers: ${diff.mkString(",")}. Mappings supplied: ${mappings}"))(_ =>
            diff.isEmpty)
          .map { cs =>
            val locations =
              cs.flatMap(_.portMappings).map((Location.localhost _).tupled)
            Service.Registered(name,
                               NonEmptyList.fromListUnsafe(cs),
                               Endpoints(NonEmptyList.fromListUnsafe(locations)),
                               readyCheck,
                               dependencies)
          }
      }
    }

    case class Registered[F[_]](name: String,
                                containers: NonEmptyList[Container.Registered],
                                endpoints: Endpoints,
                                readyCheck: ReadyCheck[F],
                                dependencies: Set[Service.Ref])
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

    def unsafeLocationFor[F[_]](containerPort: Int)(implicit A: ApplicativeError[F, Throwable]): F[Location] =
      A.fromEither(locationFor(containerPort))
  }

  object ServicesByRef {
    def empty[F[_]](implicit A: ApplicativeError[F, Throwable]): ServicesByRef[F] = ServicesByRef[F](Map.empty)(A)
  }

  case class ServicesByRef[F[_]](toMap: Map[Service.Ref, Service.Registered[F]])(
      implicit A: ApplicativeError[F, Throwable]) {
    def toList: List[Service.Registered[F]] = toMap.values.toList

    def +(registered: Service.Registered[F])(implicit tag: AppTag): ServicesByRef[F] =
      copy(toMap = toMap + (registered.ref -> registered))

    private def refNotFound(ref: Service.Ref): Throwable =
      new IllegalArgumentException(
        s"Cannot find a service with ref: ${ref.show}. Registered services: ${toMap.keys.map(_.show).mkString(", ")}")

    private def serviceFor(ref: Service.Ref): Either[Throwable, Service.Registered[F]] =
      toMap.get(ref).toRight(refNotFound(ref))

    def locationFor(ref: Service.Ref, containerPort: Int): F[Location] =
      A.fromEither(serviceFor(ref).flatMap(_.endpoints.locationFor(containerPort)))

    def envFor(serviceRef: Service.Ref): F[Map[String, String]] =
      A.fromEither(for {
        service <- serviceFor(serviceRef)

        containerRefPorts = service.containers.toList.flatMap(c =>
          c.portMappings.toList.map { case (_, port) => c.ref -> port })

        env <- containerRefPorts.foldMapM {
          case (containerRef, containerPort) =>
            service.endpoints.locationFor(containerPort).map { l =>
              Map(
                s"${service.name.toUpperCase}_HOST"                        -> s"${l.host}",
                s"${service.name.toUpperCase}_HOSTPORT_FOR_$containerPort" -> s"${l.port}"
              )
            }
        }
      } yield env)
  }

  //lawless typeclass to lift a value from a potentially impure effect
  //into an F
  abstract class ImpureEffect[F[_]] {
    def lift[A](a: => A): F[A]
    def runSync[A](effect: F[A]): A
  }

  object ImpureEffect {
    implicit def futureImpure(implicit ec: ExecutionContext): ImpureEffect[Future] =
      new ImpureEffect[Future]() {
        override def lift[A](a: => A)             = Future(a)
        override def runSync[A](fa: Future[A]): A = Await.result(fa, Duration.Inf)
      }
  }
}
