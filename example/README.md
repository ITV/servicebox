### Toy keyvalue store example

In this example, we use Servicebox to test a toy key values store
which persists data in Postgres and writes metrics in InfluxDB. The example
provides a solution for some common issues you might find while using the library:

- Defining services and running them on a local Docker instance.
- Resolving the running services host/ports (which are dynamically assigned by the library) and updating
  your app configuration accordingly.
- tearing down the running containers at the end of the test suite, using scalatest `afterAll` hook
  (this is shown as an alternative to the JVM shutdown hook shown in the main README).
