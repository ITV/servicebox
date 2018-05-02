# Key-value store example

In this example, we use Servicebox to test a toy key-value store
which persists data in Postgres and writes metrics in InfluxDB. The example
provides a solution for some common issues you might encounter while using the library:

- Defining ready-checks for your services, and running them on a local Docker instance.
- Resolving the running services host/ports (which are dynamically assigned by the library) and updating
  your app configuration accordingly.
- tearing down the running containers at the end of the test suite, using scalatest `afterAll` hook
  (this is intended as an alternative to the JVM shutdown hook shown in the main README).
