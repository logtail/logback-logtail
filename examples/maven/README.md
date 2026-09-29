# Better Stack Maven example 

* Find your source token and ingesting host in your source's settings at [Better Stack -> Sources](https://telemetry.betterstack.com/team/0/sources).
* Edit [src/main/resources/logback.xml](src/main/resources/logback.xml): replace `<!-- YOUR SOURCE TOKEN -->` with your source token and `YOUR_INGESTING_HOST` with your ingesting host.
* Run `mvn compile -e exec:java -Dexec.mainClass="com.logtail.example.App"` from this directory.
* You should see a `Hello World!` log along with a few other examples in [Better Stack -> Live tail](https://telemetry.betterstack.com/team/0/tail).
