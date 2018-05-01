# Service box

A type safe library to define and run test dependencies using scala and Docker containers.

## Containers and integration testing

Scala's strong type system, when used properly, can help avoiding a range of otherwise common bugs, 
often removing the need for pedantic, low-level unit testing. However, we still find highly valuable testing the integration of several software 
components. It is in fact at this level that we spot most errors (e.g. serialisation/deserialisation, 
missing configuration values, SQL queries working differently across different database vendors, race conditions, etc.).

Recently, we have started using Docker to streamline the way we run this type of tests, both on
our development machines and on our continuous integration environment. 
By allowing us to reproduce a realistic production environment with great flexibility and speed, containers
help increase confidence in our testing and continuous integration process.

### Status

[![Build Status](https://travis-ci.org/ITV/servicebox.svg?branch=master)](https://travis-ci.org/ITV/servicebox)
[![Latest version](https://index.scala-lang.org/itv/servicebox/servicebox-core/latest.svg?color=orange&v=1)](https://index.scala-lang.org/itv/servicebox/servicebox-core)

This library is at an early development stage and its API is likely to change significantly over the upcoming releases.

## Getting started

You can install servicebox by adding the following dependencies to your `build.sbt` file:

```scala
val serviceboxVersion = "<CurrentVersion>"
libraryDependencies ++= Seq(
  "com.itv" %% "servicebox-core" % serviceboxVersion,
  "com.itv" %% "servicebox-docker" % serviceboxVersion, //docker support
  "com.itv" %% "servicebox-docker-io" % serviceboxVersion, //optional module to use `cats.effect.IO` instead of `scala.concurrent.Future`
)
```

To start with, you must specify your service dependencies as follows:

```scala
import cats.effect.IO
import scala.concurrent.duration._
import cats.data.NonEmptyList

import com.itv.servicebox.algebra._
import com.itv.servicebox.interpreter._
import com.itv.servicebox.docker

import doobie._
import doobie.implicits._

object Postgres {
  case class DbConfig(host: String, dbName: String, password: String, port: Int)
  
  val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver", 
    "jdbc:postgresql:world",
    "postgres",
    "" 
  )
  
  def pingDb(value: DbConfig): IO[Unit] = IO {
    sql"select 1".query[Unit].unique.transact(xa)
  } 

  def apply(config: DbConfig): Service.Spec[IO] = {
    // this will be re-attempted if an error is raised when running the query
    def dbConnect(endpoints: Endpoints): IO[Unit] =
      for {
        _ <- IOLogger.info("Attempting to connect to DB ...")
        ep = endpoints.toNel.head
        serviceConfig = config.copy(host = ep.host, port = ep.port)
        _ <- pingDb(serviceConfig)
        _ <- IOLogger.info("... connected")
      } yield ()

    Service.Spec[IO](
      "Postgres",
      NonEmptyList.of(
        Container.Spec("postgres:9.5.4",
                       Map("POSTGRES_DB" -> config.dbName, "POSTGRES_PASSWORD" -> config.password),
                       Set(5432))),
      Service.ReadyCheck[IO](dbConnect, 50.millis, 5.seconds)
    )
  }
}
```

A `Service.Spec[F[_]]` consists of one or more container descriptions, together with a `ReadyCheck`: an effectfull function
which will be called repeatedly (i.e. every 50 millis) until it either returns successfully or it times out.

Once defined, one or several service specs might be executed through a `Runner`:

```scala
scala> import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext.Implicits.global

scala> implicit val tag: AppTag = AppTag("com.example", "some-app")
tag: com.itv.servicebox.algebra.AppTag = AppTag(com.example,some-app)

scala> val config = Postgres.DbConfig("localhost", "user", "pass", 5432)
config: Postgres.DbConfig = DbConfig(localhost,user,pass,5432)

scala> val postgresSpec = Postgres(config)
postgresSpec: com.itv.servicebox.algebra.Service.Spec[cats.effect.IO] = Spec(Postgres,NonEmptyList(Spec(postgres:9.5.4,Map(POSTGRES_DB -> user, POSTGRES_PASSWORD -> pass),Set(5432))),ReadyCheck(Postgres$$$Lambda$6067/213047182@4faa823d,50 milliseconds,5 seconds,None))

scala> //evaluate only once to prevent shutdown hook to be fired multiple times
     | lazy val runner = {
     |   val instance = docker.runner()(postgresSpec)
     |   sys.addShutdownHook {
     |     instance.tearDown.unsafeRunSync()
     |   }
     |   instance
     | }
runner: com.itv.servicebox.algebra.Runner[cats.effect.IO] = <lazy>
```

The service `Runner` exposes two main methods: a `tearDown`, which will kill all the containers
defined in the spec, and a `setUp`:

```scala
scala> val registeredServices = runner.setUp.unsafeRunSync
registeredServices: com.itv.servicebox.algebra.ServicesByRef[cats.effect.IO] = ServicesByRef(Map(Ref(com.example/some-app/Postgres) -> Registered(Postgres,NonEmptyList(Registered(Ref(com.example/some-app/Postgres/postgres:9.5.4),postgres:9.5.4,Map(POSTGRES_DB -> user, POSTGRES_PASSWORD -> pass),Set((49162,5432)))),Endpoints(NonEmptyList(Location(127.0.0.1,49162,5432))),ReadyCheck(Postgres$$$Lambda$6067/213047182@4faa823d,50 milliseconds,5 seconds,None))))
```

This returns us a wrapper of a `Map[Service.Ref, Service.Registered[F]]`
providing us with some convenience methods to resolve running services/containers:

```scala
scala> val pgLocation = registeredServices.unsafeLocationFor(postgresSpec.ref, 5432)
pgLocation: com.itv.servicebox.algebra.Location = Location(127.0.0.1,49162,5432)
```

Notice that, while in the `Postgres` spec we define a container port, the library will automatically bind it to
an available host port (see `InMemoryServiceRegistry` for details). Remember that, in order to use the service
in your tests, you will have to point your app to the dynamically assigned host/port

```scala
scala> pgLocation.port
res1: Int = 49162
```

## Detailed example

Please refer to [this module](example) for a more detailed usage example illustrating how to integrate the library
with `scalatest`.

## Key components

The library currently consists of the following modules:

- An "algebra" to define test dependencies (aka `Service`) as aggregates of one or several `Container`.
- An `InMemoryServiceRegistry` that can automatically assign available host ports to a service containers.
- A `Scheduler`, which provides a simple interface suitable to repeatedly check if a service is ready
- "Interpreters" to setup/check/teardown services using Docker as container technology, and `scala.concurrent.Future` or `cats.effect.IO`
as the effect system.

![Component diagram](docs/modules.png)

## Modules

- `core`: the core algebra, with built-in support for `scala.concurrent.Future`.
- `core-io`: optional support for `cats.effect.IO`
- `docker`: a docker interpreter for the core algebra.
