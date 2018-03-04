
val fs2Version = "0.10.1"

lazy val commonSettings = Seq(
  organization := "com.itv",
  name := "servicebox",
  version := "0.1.0-SNAPSHOT",
  scalaVersion := "2.12.4",
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
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.8.0",
    "co.fs2" %% "fs2-core" % fs2Version
  )
)

lazy val core = (project in file("core"))
  .settings(
    commonSettings,
  ).settings(moduleName := "core")

lazy val docker = (project in file("docker"))
  .settings(commonSettings ++ Seq(
    moduleName := "docker",
    libraryDependencies ++= Seq(
      "com.spotify"  % "docker-client" % "8.10.0"
    )
  )).dependsOn(core % "compile->compile;test->test")

lazy val root = (project in file("."))
  .aggregate(core, docker)
