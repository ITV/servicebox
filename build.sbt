import sbt.Attributed
import sbt.Keys.publishArtifact
import ReleaseTransformations._
import com.typesafe.sbt.pgp.PgpKeys.{publishSigned, publishLocalSigned}

val monocleVersion = "1.5.0"

lazy val commonSettings = Seq(
  organization := "com.itv",
  name := "servicebox",
  scalaVersion := "2.12.5",
  scalacOptions ++= Seq(
    "-target:jvm-1.8",
    "-encoding", "UTF-8",
    "-deprecation",
    "-feature",
    "-language:higherKinds",
    "-Yno-adapted-args",
    "-Ypartial-unification",
    "-Xfatal-warnings",
    "-Xmax-classfile-name","100"
  ),
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core" % "1.0.1",
    "org.scalatest" %% "scalatest" % "3.0.4" % "test",
    "com.github.julien-truffaut" %%  "monocle-core"  % monocleVersion % "test",
    "com.github.julien-truffaut" %%  "monocle-macro" % monocleVersion % "test",
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.8.0"
  ),

  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    releaseStepCommandAndRemaining("publish"),
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

def withDeps(p: Project)(dep: Project*): Project
  = p.dependsOn(dep.map(_ % "compile->compile;test->test"): _*)

lazy val core = (project in file("core"))
  .settings(
    commonSettings,
  ).settings(moduleName := "servicebox-core")

lazy val coreIO = withDeps((project in file("core-io"))
  .settings(
    commonSettings ++ Seq(
  moduleName := "servicebox-core-io",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "0.10"
  ))))(core)

lazy val docker = withDeps((project in file("docker"))
  .settings(commonSettings ++ Seq(
    moduleName := "servicebox-docker",
    libraryDependencies ++= Seq(
      "com.spotify" % "docker-client" % "8.10.0"
    )
  )))(core)


lazy val dockerIO = withDeps((project in file("docker-io"))
  .settings(commonSettings ++ Seq(moduleName := "servicebox-docker-io")))(core, coreIO, docker)

lazy val root = (project in file("."))
  .aggregate(core, coreIO, docker, dockerIO)
  .settings(commonSettings)
