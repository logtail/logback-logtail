# Example maven Hello world application
With integrated Logtail logging

* Edit [src/main/resources/logback.xml](src/main/resources/logback.xml) 
  and replace `<!-- YOUR LOGTAIL TOKEN -->` with your source token from [logtail.com](http://logtail.com)
* Run 
  
  `mvn compile -e exec:java -Dexec.mainClass="com.logtail.example.App"`

  from this directory
* You should see a "Hello world" log in your source 
