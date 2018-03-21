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

## features

- A simple algebra/dsl to define a test dependency (i.e. `Service`) as an aggregate of one or several containers.
- Interpreters to run such algebra using Docker as container technology, and `scala.concurrent.Future` or `cats.effect.IO`
as the effect system.

## Modules

- core
- core-io
- docker
- docker-io