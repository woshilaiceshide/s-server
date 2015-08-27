# s-server
Some Small Servers written in Scala, including a nio server and a small httpd, which also supports websocket(v13 only).

It's targeted for small footprint when running, but with extensibility for mulit-threading when processing http requests's business.

## Features
* small footprint when running. HOW SMALL? Try by yourself, and you'll get it!
* only one single thread is needed for basic running, and this thread can be used as an external task runner.
* support multi-threading when processing messages in bussiness codes
* builtin checking for idle connections
* builtin support for throttling messages
* support http pipelining
* support http chunking
* support websocket(v13 only)
* plain http connections can be switched to websocket connections. (but not vice versus)

## Two Examples
* test/main/scala/woshilaiceshide/sserver/EchoServer.scala
* test/main/scala/woshilaiceshide/sserver/SampleHttpServer.scala