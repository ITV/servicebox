package com.itv.servicebox.test

import java.net.{ServerSocket, Socket}
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import cats.MonadError
import cats.data.NonEmptyList
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.either._
import com.itv.servicebox.algebra.Service.ReadyCheck
import com.itv.servicebox.algebra._
import org.scalactic.TypeCheckedTripleEquals
import org.scalatest.{Assertion, FreeSpec, Matchers, Succeeded}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, TimeoutException}
import scala.util.{Success, Try}
import Function.const

abstract class RunnerTest[F[_]](implicit ec: ExecutionContext, M: MonadError[F, Throwable], I: ImpureEffect[F])
    extends FreeSpec
    with Matchers
    with TypeCheckedTripleEquals {

  //TODO: cleanup appTag mess!
  def dependencies(implicit tag: AppTag): Dependencies[F]

  def withServices(testData: TestData[F])(f: TestEnv[F] => F[Assertion])(implicit appTag: AppTag) =
    withRunningServices(dependencies)(testData)(f)

  def runServices(testData: TestData[F])(f: TestEnv[F] => F[Assertion])(implicit appTag: AppTag) =
    I.runSync(withServices(testData)(f))

  def withRunningContainers(setupExisting: NonEmptyList[Container.Spec] => NonEmptyList[Container.Spec])(
      spec: Service.Spec[F])(f: (TestEnv[F], List[Container.Registered]) => F[Assertion])(implicit appTag: AppTag) = {

    import cats.data.StateT
    import cats.Id

    type PortAllocation[A] = StateT[Id, Range, A]

    def assign(container: Container.Spec, serviceRef: Service.Ref): PortAllocation[Container.Registered] =
      for {
        range <- StateT.get[Id, Range]
        registered = container.register(range, serviceRef).valueOr(throw _)
        _ <- StateT.set[Id, Range](range.drop(container.internalPorts.size))
      } yield registered

    val (portRange, registered) = setupExisting(spec.containers).traverse(assign(_, spec.ref)).run(TestData.portRange)

    val preExisting = registered.map {
      RunningContainer(_, spec.ref)
    }.toList

    runServices(TestData(portRange, List(spec), preExisting)) { env =>
      for {
        _                 <- I.lift(Thread.sleep(10000))
        service           <- env.serviceRegistry.unsafeLookup(spec)
        runningContainers <- env.deps.containerController.runningContainers(service)
        assertion         <- f(env, runningContainers)
      } yield assertion
    }
  }

  def timed[A](f: F[A]): F[(A, FiniteDuration)] =
    for {
      t1 <- I.lift(System.currentTimeMillis())
      a  <- f
      t2 <- I.lift(System.currentTimeMillis())
    } yield (a, FiniteDuration(t2 - t1, TimeUnit.MILLISECONDS))

  object Specs {
    val nc  = TestData.ncSpec[F]
    val pg  = TestData.postgresSpec[F]
    val rmq = TestData.rabbitSpec[F]
  }

  import TestData.appTag

  "setUp" - {
    "initialises the service and updates the registry" in {
      val testData = TestData(Specs.pg)
      val spec     = testData.serviceAt(Specs.pg.ref)

      runServices(testData) { env =>
        for {
          service         <- env.serviceRegistry.unsafeLookup(spec)
          imageDownloaded <- env.deps.imageRegistry.imageExists(spec.containers.head.imageName)

        } yield {
          service.endpoints.toNel.head.port should ===(testData.portRange.take(1).head)
          imageDownloaded should ===(true)
        }
      }
    }

    "returns the registered services" in {
      val testData = TestData.default[F]
      runServices(testData) { env =>
        for {
          registered <- env.runner.setUp
        } yield {
          registered.toMap.keySet should ===(testData.servicesByRef.keySet)
        }
      }
    }

    "allows to discover service dependencies by injecting env vars in containers" in {
      //
      //   RMQ -> PG -> NC
      //

      val nc  = Specs.nc
      val pg  = Specs.pg.dependsOn(nc.ref)
      val rmq = Specs.rmq.dependsOn(pg.ref)

      runServices(TestData(pg, rmq, nc)) { testEnv =>
        for {
          registered <- testEnv.runner.setUp
          ncPort = nc.containers.head.internalPorts.head
          pgPort = pg.containers.head.internalPorts.head
          ncLocation <- registered.locationFor(nc.ref, ncPort)
          pgLocation <- registered.locationFor(pg.ref, pgPort)
        } yield {

          val pgEnv  = registered.toMap(pg.ref).containers.head.env.toList
          val rmqEnv = registered.toMap(rmq.ref).containers.head.env.toList

          pgEnv should contain("NETCAT-SERVICE_HOST"              -> ncLocation.host)
          pgEnv should contain("NETCAT-SERVICE_HOSTPORT_FOR_8080" -> ncLocation.port.toString)

          rmqEnv should contain("DB_HOST"                  -> pgLocation.host)
          rmqEnv should contain(s"DB_HOSTPORT_FOR_$pgPort" -> pgLocation.port.toString)
        }
      }
    }

    "assigns a host port for each container port in the spec" in {
      val data = TestData(Specs.rmq).modifyPortRange(_.reverse)
      val spec = data.serviceAt(Specs.rmq.ref)

      runServices(data) { env =>
        for {
          service <- env.serviceRegistry.unsafeLookup(spec)

        } yield {
          val hostPortsBound =
            data.portRange.take(spec.containers.toList.flatMap(_.internalPorts).size).toList

          service.endpoints.toList.map(_.port) should ===(hostPortsBound)
        }
      }
    }
    "does not assign a port that is in-range, but bound to a running service" in {
      val testData = TestData(Specs.rmq)
      val server   = new ServerSocket(TestData.portRange.head)
      try {
        runServices(testData) { env =>
          for {
            service <- env.serviceRegistry.unsafeLookup(Specs.rmq)
          } yield {
            Try(new Socket("localhost", TestData.portRange.head).close()) should ===(Success(()))
            service.endpoints.toList.map(_.port) should ===(testData.portRange.slice(1, 3).toList)
          }
        }
      } finally {
        server.close()
      }
    }
    "matches running containers" in {
      withRunningContainers(identity)(Specs.rmq) { (env, runningContainers) =>
        I.lift {
          runningContainers should have size 1
          runningContainers should ===(env.preExisting.map(_.container))
        }
      }
    }

    "tears down containers that do not match the spec because of env changes" in {
      val changeEnv: Container.Spec => Container.Spec =
        _.copy(env = Map("POSTGRES_DB" -> "other-db"))

      withRunningContainers(_.map(changeEnv))(Specs.pg) { (env, runningContainers) =>
        for {
          service <- env.serviceRegistry.unsafeLookup(Specs.pg)
        } yield {
          runningContainers should have size 1
          runningContainers should !==(env.preExisting.map(_.container))
          service.containers.map(_.toSpec) should ===(Specs.pg.containers)
        }
      }
    }

    "tears down containers that do not match the spec because of a command change" in {
      def setCommand(cmd: NonEmptyList[String])(c: Container.Spec): Container.Spec =
        c.copy(command = Some(cmd))

      val cmd1 = NonEmptyList.of("-v", "-l", "8081")

      withRunningContainers(_.map(setCommand(cmd1)))(Specs.nc) { (env, runningContainers) =>
        for {
          service <- env.serviceRegistry.unsafeLookup(Specs.nc)
        } yield {
          runningContainers should have size 1
          runningContainers should !==(env.preExisting.map(_.container))
          service.containers.map(_.toSpec) should ===(Specs.nc.containers)
        }
      }
    }

    "tears down containers that do not match the spec because of bind mounts" in {
      def bindMount(target: String) =
        BindMount(Paths.get("core/src/test/scala/com/itv/servicebox/test/resource.txt"), Paths.get(target))

      def setBindMounts(target: String)(c: Container.Spec): Container.Spec =
        c.copy(mounts = List(bindMount(target)))

      val spec = Specs.pg.mapContainers(setBindMounts("/target2.md"))

      withRunningContainers(_.map(setBindMounts("/target1.md")))(spec) { (env, runningContainers) =>
        for {
          service <- env.serviceRegistry.unsafeLookup(spec)
        } yield {
          runningContainers should have size 1
          runningContainers should !==(env.preExisting.map(_.container))
          service.containers.map(_.toSpec) should ===(spec.containers.map(_.withAbsolutePaths))
        }
      }
    }

    "raises an error if the unallocated port range cannot fit the ports in the spec" in {
      I.runSync(withServices(TestData.default[F].modifyPortRange(_.take(1))) {
          const(M.pure(Succeeded))
        }.attempt)
        .isLeft should ===(true)
    }
    "raises an error if a service container definition result in an empty port list" in {
      val testData                  = TestData[F](Specs.rmq)
      val containersNoInternalPorts = Specs.rmq.containers.map(c => c.copy(internalPorts = Set.empty))
      val spec                      = Specs.rmq.copy(containers = containersNoInternalPorts)
      val containerRefs             = containersNoInternalPorts.toList.map(_.ref(Specs.rmq.ref))

      val expected =
        ServiceRegistry.EmptyPortList(containerRefs)

      I.runSync(withServices(testData.withSpecs(spec)) {
          const(M.pure(Succeeded))
        }.attempt)
        .left
        .get should ===(expected)
    }

    "raises an error if a service ready-check times out" in {
      val testData = TestData.default[F]
      val counter  = new AtomicInteger(0)
      val rabbitSpec =
        Specs.rmq
          .copy(
            readyCheck = ReadyCheck(_ =>
                                      I.lift(counter.getAndIncrement()) >> M.raiseError[Unit](
                                        new IllegalStateException(s"Cannot access test service")),
                                    100.millis,
                                    1.second))

      val (result, elapsedTime) = I.runSync(
        timed {
          withServices(testData.withSpecs(rabbitSpec)) {
            const(M.pure(fail("this should time out!")))
          }.attempt
        }
      )

      result.left.get shouldBe a[TimeoutException]
      elapsedTime should be > 1.second
      counter.get should ===(10 +- 5)
    }

    "recovers from errors when ready-checks eventually succeed" in {
      val counter = new AtomicInteger(0)
      val rabbitSpec = Specs.rmq
        .copy(readyCheck = ReadyCheck(_ => {
          if (counter.getAndIncrement() < 3)
            M.raiseError[Unit](new IllegalStateException(s"Cannot access test service"))
          else
            M.pure(())
        }, 100.millis, 1.second))

      I.runSync(
        withServices(TestData(rabbitSpec)) { env =>
          for {
            service           <- env.serviceRegistry.unsafeLookup(rabbitSpec)
            runningContainers <- env.deps.containerController.runningContainers(service)
            readyCheckDuration = env.runtimeInfo(service.ref).readyCheckDuration
          } yield {
            service.toSpec should ===(rabbitSpec)
            runningContainers should have size 1
            counter.get() should ===(4)
            readyCheckDuration.toMillis should ===(300L +- 150L)
          }
        }
      )
    }
  }

  "tearDown" - {
    "shuts down the services and updates the registry" in {
      val testData = TestData.default[F]
      val spec     = testData.serviceAt(Specs.rmq.ref)
      runServices(testData) { env =>
        for {
          service           <- env.serviceRegistry.unsafeLookup(spec)
          _                 <- env.runner.tearDown
          maybeSrv          <- env.serviceRegistry.lookup(service.ref)
          runningContainers <- env.deps.containerController.runningContainers(service)
        } yield {
          maybeSrv should ===(None)
          runningContainers shouldBe empty
        }
      }
    }
  }
}
