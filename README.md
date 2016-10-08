# s-server
Some Small & Smart Servers written in Scala, including a nio server and a small httpd, which also supports websocket(v13 only).

It's targeted for small footprint when running, with extensibility for mulit-threading when processing http requests' business.

`s-server` uses `'@sun.misc.Contended'` to kick `false sharing` off, so run it on `jvm-8` with `-XX:-RestrictContended` if asynchronous responses are needed.

Http parsing and rendering are based on spray(https://github.com/spray/spray), but I tweaked it much for performance and code size. 

Note that I've refactored s-server very much since version 1.x. Version 2.x is not compatible with version 1.x, and the latter one is not be supported any more. 

## Features
* small footprint when running. HOW SMALL? Try by yourself, and you'll get it!
* only one single thread is needed for basic running, and this thread can be used as an external task runner and a fuzzy scheduler. (the builtin fuzzy scheduler may be disabled when constructing the server instance.)
* support multi-threading when processing messages in bussiness codes
* builtin checking for idle connections
* builtin support for throttling messages
* support http pipelining
* support http chunking
* support websocket(v13 only)
* plain http connections can be switched to websocket connections. (but not vice versus)(NO practical use???)

## Model
1.
requests flow in `'channel`'s,
 
2.
and handled by `'channel handler`'s,
 
3.
and `'channel handlers`' may use `'channel wrapper`'s to write responses. 

## How to Use It?
1. I've published s-server to bintray, you can add the following line in your build.sbt:

	resolvers += "Woshilaiceshide Releases" at "http://dl.bintray.com/woshilaiceshide/maven/"

	libraryDependencies += "woshilaiceshide" %% "s-server" % "2.1" withSources() 

2.
build s-server locally using `'sbt publishLocal'`.

## Two Examples
* `test/main/scala/woshilaiceshide/sserver/EchoServer.scala`

* `test/main/scala/woshilaiceshide/sserver/SampleHttpServer.scala` <br> for a quick glance, see https://github.com/woshilaiceshide/s-server/blob/master/src/test/scala/woshilaiceshide/sserver/test/SampleHttpServer.scala <br> This sample http server contains synchronous response, asynchronous response, chunked response, response for chunked request, websocket, and is enabled with pipelining. <br> To request using pipelining, just run `'nc -C 127.0.0.1 8787 < src/test/scala/woshilaiceshide/sserver/http-requests.dos.txt`'.

To test the above examples, just type the following command in your sbt console: 
* type `'test:run'` in your sbt console to run `'woshilaiceshide.sserver.test.EchoServer'`

* type `'test:runMain'` in your sbt console followed by a `'TAB'` to prompt you the valid choices

* type the following commands in your sbt console to make a standalone distribution with all the tests using sbt-native-packager:

		set unmanagedSourceDirectories in Compile := (unmanagedSourceDirectories in Compile).value ++ (unmanagedSourceDirectories in Test).value
		set mainClass in Compile := Some("woshilaiceshide.sserver.test.EchoServer")
		dist
or

		set unmanagedSourceDirectories in Compile := (unmanagedSourceDirectories in Compile).value ++ (unmanagedSourceDirectories in Test).value
		set mainClass in Compile := Some("woshilaiceshide.sserver.test.SampleHttpServer")
		dist

## Real Projects
1.
**https://github.com/woshilaiceshide/scala-web-repl**

I'v written a project named `"Scala-Web-REPL`", which uses a web terminal as its interactive console.

2.
**...**

If your development touches `'s-server'`, you may tell me to write your project here.

## Optimizations & TODO
* optimize socket i/o operations: 1). reducing i/o operations, 2). making i/o just carried out as directly as possible without repost them to the underlying executor.
* optimize the http parsing processes. Spray's http parser is not good enough, and some tweaks are needed.
* reuse more objects no matter what footprints they occupy, especially for i/o, parsing, rendering.
* write test cases
* ...

## Attention Please!
When the input stream is shutdown by the client, the server will read "-1" bytes from the stream.
But, "-1" can not determine among "the entire socket is broken" or "just the input is shutdown by peer, but the output is still alive".

I tried much, but did not catch it!

Business codes may "ping" to find out weather the peer is fine, or just shutdown the whole socket in this situation.
The key hook is "woshilaiceshide.sserver.nio.ChannelHandler.inputEnded(channelWrapper: NioSocketServer.ChannelWrapper)".
In most situations, You can just close the connection  
