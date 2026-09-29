# Better Stack Gradle example

* Java 11 or newer is needed, as for logback 1.5. On Java 8, set the logback version to 1.3.x in [build.gradle](build.gradle). logback-logtail leaves logback to the application, so the example declares `logback-classic` itself.
* Find your source token and ingesting host in your source's settings at [Better Stack -> Sources](https://telemetry.betterstack.com/team/0/sources).
* Edit [src/main/resources/logback.xml](src/main/resources/logback.xml): replace `<!-- YOUR SOURCE TOKEN -->` with your source token and `YOUR_INGESTING_HOST` with your ingesting host.
* Run `gradle run` from this directory.
* You should see a `Hello World!` log along with a few other examples in [Better Stack -> Live tail](https://telemetry.betterstack.com/team/0/tail).

