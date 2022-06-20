![Java Build](https://github.com/logtail/logback-logtail/workflows/Java%20Build/badge.svg)

# Logtail Logback appender

This library provides an appender for [logback](https://logback.qos.ch), allowing to send your logs to the [Logtail](https://logtail.com) online logging platform, via our HTTPS Ingest API. MDC thread-bound data can be send, indexed and searched into your Logtail dashboard.


## Introduction

This library provides an appender for [logback](https://logback.qos.ch), allowing you to send logs to our logging platform [Logtail](https://logtail.com) logging platform, via our HTTPS Ingest API. MDC thread-bound data can be send, indexed and searched into your Logtail dashboard.

This library relies on a JAX-RS v2 implementation (of your choice) with a Jackson JSON mapper.

### How to use it

First, copy this dependency into your `pom.xml` file.

```xml
<dependency>
    <groupId>com.logtail</groupId>
    <artifactId>logback-logtail</artifactId>
    <version>{revnumber}</version>
</dependency>
```

Add Logback to your dependencies, and if you don't already have one in your project, add a JAX-RS implementation into your `pom.xml` like [Jersey](https://search.maven.org/artifact/org.glassfish.jersey.core/jersey-client), which is the one we use in the unit tests, [RESTEasy](https://search.maven.org/artifact/org.jboss.resteasy/resteasy-client) or [Apache CXF](https://search.maven.org/artifact/org.apache.cxf/cxf-rt-rs-client).

Example dependencies:

```xml
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
    <version>1.2.11</version>
</dependency>

<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-core</artifactId>
    <version>1.2.11</version>
</dependency>

<dependency>
    <groupId>com.fasterxml.jackson.jaxrs</groupId>
    <artifactId>jackson-jaxrs-json-provider</artifactId>
    <version>2.13.3</version>
</dependency>

<dependency>
    <groupId>jakarta.ws.rs</groupId>
    <artifactId>jakarta.ws.rs-api</artifactId>
    <version>3.1.0</version>
</dependency>

<dependency>
    <groupId>jakarta.inject</groupId>
    <artifactId>jakarta.inject-api</artifactId>
    <version>2.1.0</version>
</dependency>

<dependency>
    <groupId>jakarta.activation</groupId>
    <artifactId>jakarta.activation-api</artifactId>
    <version>2.1.0</version>
</dependency>

<dependency>
    <groupId>org.glassfish.jersey.core</groupId>
    <artifactId>jersey-client</artifactId>
    <version>3.0.4</version>
</dependency>

<dependency>
    <groupId>org.glassfish.jersey.inject</groupId>
    <artifactId>jersey-hk2</artifactId>
    <version>3.0.4</version>
</dependency>
```

Then, copy the following two Logtail appenders to your `classpath:/logback.xml` file. Here's a sample configuration:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

    <appender name="LogtailHttp" class="com.logtail.logback.LogtailAppender">
        <appName>MyApp</appName>
        <ingestKey>${LOGTAIL_INGEST_KEY}</ingestKey>
        <batchSize>10</bathSize>
        <mdcFields>requestId,requestTime</mdcFields>
        <mdcTypes>string,int</mdcTypes>
    </appender>

    <appender name="Logtail" class="ch.qos.logback.classic.AsyncAppender">
        <appender-ref ref="LogtailHttp" />
        <queueSize>500</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <includeCallerData>true</includeCallerData>
    </appender>

    <appender name="Console" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm} %-5level %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="Logtail" />
        <appender-ref ref="Console" />
    </root>

</configuration>
```

Finally, you have to provide a `LOGTAIL_INGEST_KEY`, an API key you'll find in the Logtail console when creating a source. If you don't provide an API key, the appender will be automatically disabled (with a warning).

The `queueSize` and `discardingThreshold` are native Logback AsyncAppender attributes. Read the [official docs](https://logback.qos.ch/manual/appenders.html#AsyncAppender) to learn more.
    

### Configuration options

* You can use your own logging pattern, by using the `encoder` option, e.g.

```xml
<configuration>
  <appender name="LogtailHttp" class="com.logtail.logback.LogtailAppender">
    <encoder>
      <pattern>[%thread] %msg%n</pattern>
    </encoder>
    
    <!-- ... -->
  </appender>
  
  <!-- ... -->
</configuration>
```

* Set up comma-separated MDC keys to index (from the MDC thread local binding).
* Set up one type for each MDC key. Possible types are string, boolean, int and long. The last two result in an indexed number in your Logtail console. You can then use these values as metrics and create Grafana dashboards around those.
* You can try to set up a specific `connectTimeout` and `readTimeout` for the underlying JAX-RS client.

### JSON parameters parsing

We automatically parse a JSON object at the end of any string log message. 

For example, if you log the following message : `My message { "id": 1, "value": "text" }`, then Logtail will index both `id` and `value` into separate parameters you can then query.
