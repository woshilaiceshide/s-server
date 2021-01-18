# s-server
Some Small & Smart Servers written in Scala, including a nio server and a small httpd, which also supports websocket(v13 only).

It's targeted for small footprint when running, with extensibility for mulit-threading when processing http requests' business.

`s-server` uses `'@sun.misc.Contended'` to kick `false sharing` off, so run it on `jvm-8` with `-XX:-RestrictContended` if asynchronous responses are needed.

Http parsing and rendering are based on [spray](https://github.com/spray/spray), but I tweaked it much for performance and code size. 

Note that s-server 3.x is the current supported release, other version is deprecated. 

## Benchmark
s-server is optimized for tcp services extremely. In TechEmpower Framework Benchmarks (TFB) 2017, s-server got good scores, such as the following:

![TechEmpower Framework Benchmarks (TFB) 2017](https://raw.githubusercontent.com/woshilaiceshide/s-server/master/asset/techempower-17.jpg "TechEmpower Framework Benchmarks (TFB) 2017")

## How to Use It?
Three Examples: 
* [EchoServer](https://github.com/woshilaiceshide/s-server/blob/master/src/test/scala/woshilaiceshide/sserver/test/EchoServer.scala)

* [SampleHttpServer](https://github.com/woshilaiceshide/s-server/blob/master/src/test/scala/woshilaiceshide/sserver/test/SampleHttpServer.scala) 
  <br> 
  This sample http server contains synchronous response only. <br> To request using pipelining, just run `'nc -C 127.0.0.1 8787 < src/test/scala/woshilaiceshide/sserver/http-requests.dos.txt`'.

* [AdvancedHttpServer](https://github.com/woshilaiceshide/s-server/blob/master/src/test/scala/woshilaiceshide/sserver/test/AdvancedHttpServer.scala)
  <br>
  This sample http server contains synchronous response, asynchronous response, chunked response, response for chunked request, websocket, and is enabled with pipelining. <br> To request using pipelining, just run `'nc -C 127.0.0.1 8787 < src/test/scala/woshilaiceshide/sserver/http-requests.dos.txt`'.

To test the above examples, just type the following command in your sbt console:
* type `'test:run'` in your sbt console to run `'woshilaiceshide.sserver.test.EchoServer'`

* type `'test:runMain'` in your sbt console followed by a `'TAB'` to prompt you the valid choices

* type the following commands in your sbt console to make a standalone distribution with all the tests using sbt-native-packager:

  	set unmanagedSourceDirectories in Compile := (unmanagedSourceDirectories in Compile).value ++ (unmanagedSourceDirectories in Test).value
  	set mainClass in Compile := Some("woshilaiceshide.sserver.test.EchoServer")
  	#or set mainClass in Compile := Some("woshilaiceshide.sserver.test.SampleHttpServer")
  	#or set mainClass in Compile := Some("woshilaiceshide.sserver.test.AdvancedHttpServer")
  	stage

## How to Build It?
If proxy is needed:
```shell
sbt \
    -Dsbt.repository.config=./repositories \
    -Dsbt.override.build.repos=true \
    -Dhttp.proxyHost=${proxy_host} -Dhttp.proxyPort=${proxy_port} -Dhttp.proxyUser=${proxy_user} -Dhttp.proxyPassword=${proxy_password} \
    -Dhttp.nonProxyHosts="localhost|127.0.0.1" \
    -Dhttps.proxyHost=${proxy_host} -Dhttps.proxyPort=${proxy_port} -Dhttps.proxyUser=${proxy_user} -Dhttps.proxyPassword=${proxy_password} \
    -Dhttps.nonProxyHosts="localhost|127.0.0.1" \
    -Dsbt.gigahorse=false \
    -v -d stage
```
If no proxy is needed: 
```shell
sbt -Dsbt.repository.config=./repositories -Dsbt.override.build.repos=true -v -d stage
```

## Features
* small footprint when running. HOW SMALL? Try by yourself, and you'll get it!
* only one single thread is needed for basic running, and this thread can be used as an external task runner and a fuzzy scheduler. (the builtin fuzzy scheduler may be disabled when constructing the server instance.)
* support multi-threading when processing messages in business codes
* builtin checking for idle connections
* builtin support for throttling messages
* support http pipelining
* support http chunking
* support websocket(v13 only)
* plain http connections can be switched(upgraded) to websocket connections. (but not vice versus)

## About '100% CPU with epoll'
The previous releases(<= v2.5) of s-server have dealt with [100% CPU with epoll](https://bugs.java.com/bugdatabase/view_bug.do?bug_id=6403933).
The main related codes can be seen on [this link](https://github.com/woshilaiceshide/s-server/blob/v2.5/src/main/scala/woshilaiceshide/sserver/nio/SelectorRunner.scala#L105).
Since 3.x, s-server clears out all those codes. So do not run s-server on jdk 6 and 7.

## Model
* requests flow in `channel`
* and handled by `channel handler`
* and `channel handler` may use `channel wrapper` to write responses. 

## How to Add It to My Projects?
1. I've published s-server to bintray, you can add the following line in your build.sbt:

	resolvers += "Woshilaiceshide Releases" at "http://dl.bintray.com/woshilaiceshide/maven/"

	libraryDependencies += "woshilaiceshide" %% "s-server" % "3.1" withSources() 

2. build s-server locally using `'sbt publishLocal'`.

## Real Projects
* I'v written a project named [Scala-Web-REPL](https://github.com/woshilaiceshide/scala-web-repl), which uses a web terminal as its interactive console.
* ... If your development touches `'s-server'`, you may tell me to write your project here.

## Optimizations & TODO
* optimize socket i/o operations: 1). reducing i/o operations, 2). making i/o just carried out as directly as possible without repost them to the underlying executor.
* optimize the http parsing processes. Spray's http parser is not good enough, and some tweaks are needed.
* reuse more objects no matter what footprints they occupy, especially for i/o, parsing, rendering.
* write test cases
* ...

## JVM Introspection
* use [jol](http://openjdk.java.net/projects/code-tools/jol/) to inspect MEMORY
* use [jitwatch](https://github.com/AdoptOpenJDK/jitwatch/releases) about BYTE CODE & JIT, see https://medium.com/@malith.jayasinghe/performance-improvements-via-jit-optimization-aa9766b705d2

## Attention Please!
When the input stream is shutdown by the client, the server will read "-1" bytes from the stream.
But, "-1" can not determine among "the entire socket is broken" or "just the input is shutdown by peer, but the output is still alive".

I tried much, but did not catch it!

Business codes may "ping" to find out weather the peer is fine, or just shutdown the whole socket in this situation.
The key hook is "woshilaiceshide.sserver.nio.ChannelHandler.inputEnded(channelWrapper: NioSocketServer.ChannelWrapper)".
In most situations, You can just close the connection  
