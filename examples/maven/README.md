# Better Stack Maven example 

* Get your source token at [Better Stack -> Sources](http://logs.betterstack.com/team/0/sources).
* Edit [src/main/resources/logback.xml](src/main/resources/logback.xml) and replace `<!-- YOUR SOURCE TOKEN -->` with your source token.
* Run `mvn compile -e exec:java -Dexec.mainClass="com.logtail.example.App"` from this directory.
* You should see a "Hello world" log in [Better Stack -> Live tail](https://logs.betterstack.com/team/0/tail).
