# Better Stack Gradle example

* Java 11 or newer is needed, as for logback 1.5. On Java 8, add `-PlogbackVersion=1.3.16` to the command below, or set it in [build.gradle](build.gradle). logback-logtail leaves logback to the application, so the example declares `logback-classic` itself.
* Find your source token and ingesting host in your source's settings at [Better Stack -> Sources](https://telemetry.betterstack.com/team/0/sources).
* [src/main/resources/logback.xml](src/main/resources/logback.xml) reads them from the environment, so export them in your shell:
  ```sh
  export BETTER_STACK_SOURCE_TOKEN=<your source token>
  export BETTER_STACK_INGESTING_HOST=<your ingesting host>
  ```
* Run `gradle run` from this directory.
* You should see a `Hello World!` log along with a few other examples in [Better Stack -> Live tail](https://telemetry.betterstack.com/team/0/tail).

