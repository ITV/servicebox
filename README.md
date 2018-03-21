# Service box

A type safe library to define and run test dependencies using scala and Docker containers.

### Status

- still incomplete, will publish a first release soon!

## Containers and integration testing

Scala's strong type system, when used properly, can help avoiding a range of obvious bugs 
(e.g. null pointer exceptions), often removing the need for pedantic, low-level unit testing. 
However, we still find highly valuable testing the integration of several software 
components. It is in fact at this level that we spot most bugs (e.g. serialisation/deserialisation, 
missing configuration values, SQL queries working differently cross vendors, race conditions, etc.).

Over the last years, we have started using Docker to streamline the way we run this type of tests, both on
our development machines and on our continuous integration environment. 
By allowing us to reproduce a realistic production environment with great flexibility and speed, containers
are helping us increase our confidence in our testing and continuous delivery process.

## Functionality and key library components

The `servicebox.algebra` package contains the following:

- A set of case classes/dsl to define a test dependency (aka `Service`) as an aggregate of one or serveral `Containers`.
- An `InMemoryServiceRegistry` that can automatically assign available host ports to a service containers.
- A `Scheduler`, which provides a simple interface to repeatedly retry effectful operations (i.e. checking if a service is ready)
- An interpreters to setup/check/teardown test dependencies using Docker as container technology, and `scala.concurrent.Future` or `cats.effect.IO`
as the effect system.

![Component diagram](docs/modules.png)

## Modules

- `core`: the core algebra, with only support for `scala.concurrent.Future`.
- `core-io`: optional support for `cats.effect.IO`
- `docker`: a docker interpreter for the core algebra.