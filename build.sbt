import sbt.Keys.publishArtifact
import ReleaseTransformations._

val monocleVersion  = "2.1.0"
val doobieVersion   = "1.0.0-RC2"
val influxDbVersion = "2.9"

val readme     = "README.md"
val readmePath = file(".") / readme
val copyReadme =
  taskKey[File](s"Copy readme file to project root")

val Scala213               = "2.13.3"

val baseSettings = Seq(
  organization := "com.itv",
  name := "servicebox",
  scalaVersion := Scala213,
  scalacOptions ++= Seq(
    "-target:jvm-1.8",
    "-encoding",
    "UTF-8",
    "-deprecation",
    "-feature",
    "-language:higherKinds",
    "-Xfatal-warnings",
  ),
  libraryDependencies ++= Seq(
    "org.typelevel"              %% "cats-core"      % "2.9.0",
    "org.typelevel"              %% "cats-effect"    % "3.5.0",
    "org.typelevel"              %% "kittens"        % "3.0.0",
    "org.scalatest"              %% "scalatest"      % "3.2.15" % "test",
    "com.github.julien-truffaut" %% "monocle-core"   % monocleVersion,
    "com.github.julien-truffaut" %% "monocle-macro"  % monocleVersion,
    "ch.qos.logback"             % "logback-classic" % "1.4.6",
    "com.typesafe.scala-logging" %% "scala-logging"  % "3.9.5",
    compilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full)
  )
)

val artefactSettings = baseSettings ++ Seq(
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    setNextVersion,
    commitNextVersion,
    pushChanges
  ),
  releaseCrossBuild := true,
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ =>
    false
  },
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  pgpPublicRing := file("./ci/public.asc"),
  pgpSecretRing := file("./ci/private.asc"),
  pgpSigningKey := Some(-5373332187933973712L),
  pgpPassphrase := Option(System.getenv("GPG_KEY_PASSPHRASE")).map(_.toArray),
  homepage := Some(url("https://github.com/itv/servicebox")),
  scmInfo := Some(ScmInfo(url("https://github.com/itv/servicebox"), "git@github.com:itv/servicebox.git")),
  developers := List(Developer("afiore", "Andrea Fiore", "andrea.fiore@itv.com", url("https://github.com/afiore"))),
  licenses += ("ITV Open Source Software Licence", url("http://itv.com/itv-oss-licence-v1.0")),
  publishMavenStyle := true,
  credentials ++= (for {
    username <- Option(System.getenv().get("SONATYPE_USER"))
    password <- Option(System.getenv().get("SONATYPE_PASS"))
  } yield Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", username, password)).toSeq,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  }
)

def withDeps(p: Project)(dep: Project*): Project = p.dependsOn(dep.map(_ % "compile->compile;test->test"): _*)

lazy val core = (project in file("core"))
  .settings(
    artefactSettings,
  )
  .settings(
    moduleName := "servicebox-core"
  )

lazy val docker = withDeps(
  (project in file("docker"))
    .settings(
      artefactSettings ++ Seq(
        moduleName := "servicebox-docker",
        libraryDependencies ++= Seq(
          "com.spotify" % "docker-client" % "8.16.0"
        )
      )))(core)

lazy val example = withDeps(
  (project in file("example"))
    .enablePlugins(MdocPlugin)
    .settings(baseSettings ++ Seq(
      libraryDependencies ++= Seq(
        "org.flywaydb" % "flyway-core"      % "4.2.0",
        "org.postgresql" % "postgresql" % "42.5.4",
        "org.tpolecat" %% "doobie-core"     % doobieVersion,
        "org.tpolecat" %% "doobie-postgres" % doobieVersion,
        "org.influxdb" % "influxdb-java"    % influxDbVersion
      ),
      mdocIn := baseDirectory.value / "src" / "main" / "mdoc",
      scalacOptions in Compile ~= {
        // https://github.com/scalameta/mdoc/issues/210
        _.filterNot(Set("-Xfatal-warnings"))
      },
      copyReadme := {
        val mdocDir = mdocOut.value
        val log     = streams.value.log

        log.info(s"Copying ${mdocDir / readme} to ${file(".") / readme}")

        IO.copyFile(
          mdocDir / readme,
          readmePath
        )
        readmePath
      }
    )))(core, docker)

lazy val root = (project in file("."))
  .aggregate(core, docker)
  .settings(artefactSettings)
