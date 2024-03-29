package com.itv.servicebox

import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import cats.data.{NonEmptyList, StateT}
import cats.derived.semiauto
import cats.effect.{IO, Sync}
import cats.instances.all._
import cats.syntax.all._
import cats.{ApplicativeError, Eq, Monad, Show}
import monocle.function.all._

import scala.concurrent.duration.FiniteDuration

package object algebra {
  private val L = Lenses

  type PortMapping = (Int, Int)

  type ContainerMappings = Map[Container.Ref, Set[PortMapping]]

  type NetworkName = String

  type PortAllocation[F[_], A] = StateT[F, AtomicReference[Range], A]

  case class AppTag(org: String, appName: String)
  object AppTag {
    implicit val appTagShow: Show[AppTag] = Show.show(tag => s"${tag.org}/${tag.appName}")
  }

  case class BindMount(from: Path, to: Path, readOnly: Boolean = false)

  object BindMount {
    implicit val pathEq: Eq[Path]  = Eq.by(_.toAbsolutePath)
    implicit val eq: Eq[BindMount] = Eq.fromUniversalEquals[BindMount] //cats.derived.semi.eq[BindMount]
    implicit val show: Show[BindMount] = Show.show { bm =>
      s"${bm.from} -> ${bm.to} ${if (bm.readOnly) "[RO]" else ""}"
    }

    def fromTmpFileContent[F[_]](baseDir: Path)(to: Path, ro: Boolean = false)(
        files: (String, Array[Byte])*)(implicit S: Sync[F], M: Monad[F]): F[BindMount] = {

      val mountDir = baseDir.resolve(UUID.randomUUID().toString)

      for {

        _ <- S
          .delay(baseDir.toFile.exists())
          .ifM(S.unit, S.delay(baseDir.toFile.mkdirs()).void)

        _ <- S.delay(Files.createDirectory(mountDir))

        _ <- files.toList.traverse_ {
          case (fileName, content) =>
            S.delay(Files.write(mountDir.resolve(fileName), content))
        }
      } yield BindMount(mountDir, to, ro)
    }
  }

  private[algebra] sealed trait Container {
    def imageName: String
    def ref(ref: Service.Ref): Container.Ref = Container.Ref(s"${ref.show}/$imageName")
    def env: Map[String, String]
    def command: Option[NonEmptyList[String]]
    def mounts: Option[NonEmptyList[BindMount]]
    def name: Option[String]
  }

  sealed trait PortSpec {
    def internalPort: Int
    def autoAssigned: Boolean
  }

  object PortSpec {

    val onlyInternalEq: Eq[PortSpec] = Eq.by(_.internalPort)

    implicit val show: Show[PortSpec] = Show.show {
      case PortSpec.Assign(internal, host) => s"$host:$internal"
      case PortSpec.AutoAssign(internal)   => s":$internal"
    }
    implicit val eq: Eq[PortSpec] = Eq.fromUniversalEquals[PortSpec] //cats.derived.semi.eq[PortSpec]

    case class AutoAssign(internalPort: Int) extends PortSpec {
      override def autoAssigned = true
    }
    case class Assign(internalPort: Int, hostPort: Int) extends PortSpec {
      override def autoAssigned = false
    }
    def assign(internal: Int, host: Int): PortSpec = Assign(internal, host)
    def assign(port: Int): PortSpec                = Assign(port, port)
    def autoAssign(internal: Int): PortSpec        = AutoAssign(internal)
  }

  object Container {
    case class Ref(value: String)
    object Ref {
      implicit val refShow: Show[Ref] = Show.show(_.value)
    }

    case class Spec(imageName: String,
                    env: Map[String, String],
                    ports: Set[PortSpec],
                    command: Option[NonEmptyList[String]],
                    mounts: Option[NonEmptyList[BindMount]],
                    name: Option[String] = None)
        extends Container {

      def withAbsolutePaths: Spec =
        (L.mounts composeTraversal each composeLens L.mountFrom)
          .modify(_.toAbsolutePath)(this)

      def register(hostPorts: Seq[Int], serviceRef: Service.Ref): Either[Throwable, Container.Registered] =
        if (hostPorts.size < ports.count(_.autoAssigned))
          Left(new IllegalArgumentException(s"supplied port range is too small! $hostPorts, internal ports: $ports"))
        else {
          val (autoAssigned, assigned) = ports.partition(_.autoAssigned)
          val mappings = autoAssigned.map(_.internalPort).zip(hostPorts).map(_.swap) ++ assigned.collect {
            case PortSpec.Assign(internal, host) => host -> internal
          }

          Right(
            Registered(
              ref(serviceRef),
              imageName,
              env,
              mappings,
              command,
              mounts,
              name
            ))
        }
    }

    object Spec {

      implicit val eqSpec: Eq[Spec] = semiauto.eq[Spec]
    }

    case class Registered(ref: Container.Ref,
                          imageName: String,
                          env: Map[String, String],
                          portMappings: Set[PortMapping],
                          command: Option[NonEmptyList[String]],
                          mounts: Option[NonEmptyList[BindMount]],
                          name: Option[String] = None)
        extends Container {
      lazy val toSpec = Spec(
        imageName,
        env,
        //TODO: we should retain the port-spec type (i.e. auto, or assign)
        portMappings.map { case (_, internal) => PortSpec.AutoAssign(internal) },
        command,
        mounts,
        name
      ).withAbsolutePaths
    }

    case class Diff(toNel: NonEmptyList[Diff.Entry])

    object Diff {
      implicit val show: Show[Diff] = Show.show[Diff](_.toNel.mkString_("\n", "\n", "\n"))

      case class Entry(fieldName: String, message: String)
      object Entry {

        implicit val show: Show[Entry] = Show.show(e => s" - ${e.fieldName}: ${e.message}")

        def apply[T](fieldName: String, actual: T, expected: T)(implicit eq: Eq[T], diff: DiffShow[T]): Option[Entry] =
          if (actual === expected) None
          else Some(Entry(fieldName, diff.showDiff(actual, expected)))
      }

      def apply(a: Container.Spec, b: Container.Spec): Option[Diff] = {
        implicit val portSpecEq: Eq[PortSpec] = PortSpec.onlyInternalEq

        if (a === b) None
        else {
          NonEmptyList
            .fromList(
              List(
                Entry("imageName", a.imageName, b.imageName),
                Entry("env", a.env, b.env),
                Entry("ports", a.ports, b.ports),
                Entry("command", a.command, b.command),
                Entry("mounts", a.mounts, b.mounts)
              ).flatten)
            .map(Diff(_))
        }
      }
    }

    trait DiffShow[T] {
      def showDiff(actual: T, expected: T): String
    }
    object DiffShow {
      import cats.syntax.show._

      case class Report(missing: Option[NonEmptyList[String]],
                        unexpected: Option[NonEmptyList[String]],
                        different: Option[NonEmptyList[String]])

      object Report {
        def fromIterables[A: Show](missing: Iterable[A], unexpected: Iterable[A], different: Iterable[A]) =
          Report(
            NonEmptyList.fromList(missing.toList.map(_.show)),
            NonEmptyList.fromList(unexpected.toList.map(_.show)),
            NonEmptyList.fromList(different.toList.map(_.show))
          )

        implicit val show: Show[Report] = Show.show[Report] { r =>
          def fmtNel(nel: NonEmptyList[String]) = nel.mkString_("\n", "\n", "")
          List(
            r.missing.map(nel => s"  Unexpected: ${fmtNel(nel)}"),
            r.different.map(nel => s"  Mismatches: ${fmtNel(nel)}"),
            r.unexpected.map(nel => s"  Unexpected: ${fmtNel(nel)}")
          ).flatten.mkString("\n", "\n", "")
        }
      }

      def instance[T](f: (T, T) => String): DiffShow[T] = new DiffShow[T] {
        override def showDiff(actual: T, expected: T): String = f(actual, expected)
      }

      implicit def mapShowDiff[K, V](implicit kShow: Show[K], vShow: DiffShow[V]): DiffShow[Map[K, V]] =
        instance[Map[K, V]] { (actual, expected) =>
          val mismatches = expected.toList.flatMap {
            case (k, v) =>
              actual.get(k).filter(_ != v).map(v1 => s"   ${k.show}: ${vShow.showDiff(v1, v)}")
          }

          Report
            .fromIterables(
              (expected.keySet -- actual.keySet).map(_.show),
              (actual.keySet -- expected.keySet).map(_.show),
              mismatches
            )
            .show
        }

      implicit def showDiff[A: Show]: DiffShow[A] = instance[A]((a, b) => s"${a.show} ≠ ${b.show}")

      implicit def setShowDiff[A: Show]: DiffShow[Set[A]] = instance[Set[A]] { (actual, expected) =>
        Report.fromIterables(expected -- actual, actual -- expected, Nil).show
      }

      implicit def nelShowDiff[A: Show]: DiffShow[NonEmptyList[A]] = { (actual, expected) =>
        def toMap(nel: NonEmptyList[A]) = nel.toList.zipWithIndex.map(_.swap).toMap
        mapShowDiff[Int, A].showDiff(toMap(actual), toMap(expected))
      }

      implicit def optShowDiff[A: Show]: DiffShow[Option[A]] = instance { (a, b) =>
        (a, b) match {
          case (Some(x), Some(y)) => showDiff[A].showDiff(x, y)
          case (None, Some(y))    => s" none [expected: ${y.show}]"
          case (Some(x), None)    => s"${x.show} [not expected]"
          case _                  => throw new InternalError("unreachable code")
        }
      }
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
          Diff(actual.toSpec, expected.toSpec)
            .fold[Result[Repr]](Success(matched, expected, actual))(Mismatch(matched, expected, actual, _))
      }

      case class Success[Repr](matched: Repr, expected: Container.Registered, actual: Container.Registered)
          extends Result[Repr] {
        override val isSuccess = true
      }

      case class Mismatch[Repr](matched: Repr, expected: Container.Registered, actual: Container.Registered, diff: Diff)
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
            .map(ms => Container.Registered(c.ref(ref), c.imageName, c.env, ms, c.command, c.mounts, c.name))
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

    def attemptLocationFor(internal: Int): Either[Throwable, Location] =
      attemptLocationFor(PortSpec.AutoAssign(internal))

    def attemptLocationFor(port: PortSpec): Either[Throwable, Location] = {
      val portMappings = toNel.map(l => s"${l.port} -> ${l.containerPort}").toList.mkString(", ")
      toNel
        .find(_.containerPort == port.internalPort)
        .toRight(new IllegalArgumentException(
          s"Cannot find a location for ${port.internalPort}. Existing port mappings: $portMappings"))
    }

    def locationFor[F[_]](internal: Int)(implicit A: ApplicativeError[F, Throwable]): F[Location] =
      locationFor[F](PortSpec.AutoAssign(internal))

    def locationFor[F[_]](port: PortSpec)(implicit A: ApplicativeError[F, Throwable]): F[Location] =
      A.fromEither(attemptLocationFor(port))
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

    def locationFor(ref: Service.Ref, internal: Int): F[Location] =
      locationFor(ref, PortSpec.AutoAssign(internal))

    def locationFor(ref: Service.Ref, port: PortSpec): F[Location] =
      A.fromEither(serviceFor(ref).flatMap(_.endpoints.attemptLocationFor(port)))

    def envFor(serviceRef: Service.Ref): F[Map[String, String]] =
      A.fromEither(for {
        service <- serviceFor(serviceRef)

        containerRefPorts = service.containers.toList.flatMap(c =>
          c.portMappings.toList.map { case (_, port) => c.ref -> port })

        env <- containerRefPorts.foldMapM {
          case (containerRef, containerPort) =>
            service.endpoints.attemptLocationFor(containerPort).map { l =>
              Map(
                s"${service.name.toUpperCase}_HOST"                        -> s"${l.host}",
                s"${service.name.toUpperCase}_HOSTPORT_FOR_$containerPort" -> s"${l.port}"
              )
            }
        }
      } yield env)
  }

  trait UnsafeBlocking[F[_]] {
    def runSync[A](fa: F[A]): A
  }

  object UnsafeBlocking {
    import cats.effect.unsafe.implicits._
    implicit val ioRunSync: UnsafeBlocking[IO] = new UnsafeBlocking[IO] {
      override def runSync[A](io: IO[A]): A = io.unsafeRunSync()
    }
  }
}
