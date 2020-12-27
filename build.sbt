organization := "woshilaiceshide"

name := "s-server"

version := "3.1"

description := "Some Small Servers written in Scala, including a nio server and a small httpd, which also supports websocket(v13 only)."

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))

publishMavenStyle := true

enablePlugins(BintrayPlugin)

pomIncludeRepository  := {_ => false}

bintrayRepository := "maven"

bintrayOrganization := Some("woshilaiceshide")

bintrayVcsUrl := Some(s"git@github.com:woshilaiceshide/${name.value}.git")

bintrayReleaseOnPublish in ThisBuild := false

compileOrder in Compile := CompileOrder.Mixed

transitiveClassifiers := Seq("sources")

scalaVersion := "2.12.12"

scalacOptions := Seq("-unchecked", "-deprecation", "-opt:l:inline", "-opt-inline-from:**", "-encoding", "utf8", "-Yno-adapted-args", "-target:jvm-1.8")

javacOptions ++= Seq("-Xlint:deprecation", "-Xlint:unchecked", "-source", "1.8", "-target", "1.8", "-g:vars")

retrieveManaged := false

enablePlugins(JavaAppPackaging)

unmanagedSourceDirectories in Compile += baseDirectory( _ / "src" / "scala" ).value

unmanagedSourceDirectories in Compile += baseDirectory( _ / "spray" / "scala" ).value

//libraryDependencies ++= 
//  Seq(
//  //"io.spray"            %%   "spray-routing" % "1.3.3",
//  //"io.spray"            %%   "spray-caching" % "1.3.3",
//    "io.spray"            %%   "spray-can"     % "1.3.3",
//  //"io.spray"            %%   "spray-client"  % "1.3.3",
//    "io.spray"            %%   "spray-http"    % "1.3.3",
//  //"io.spray"            %%   "spray-httpx"   % "1.3.3",
//  //"io.spray"            %%   "spray-io"      % "1.3.3",
//  //"io.spray"            %%   "spray-testkit" % "1.3.3",
//  //"io.spray"            %%   "spray-util"    % "1.3.3"
//  )

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.4.17"

libraryDependencies += "org.parboiled" %% "parboiled-scala" % "1.3.1"

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"

libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.30"

javaOptions in Universal += "-J-XX:-RestrictContended"
javaOptions in Universal += "-J-Xmx1024m"
javaOptions in Universal += "-J-Xms1024m"
//javaOptions in Universal += "-J-XX:+UnlockDiagnosticVMOptions"
//javaOptions in Universal += "-J-XX:+PrintInlining"
javaOptions in Universal += "-Dproperty1=value1"
javaOptions in Universal += "-property2=value2"
javaOptions in Universal += s"-version=${version.value}"

//use jol to inspect object layout schemes.
//see 'http://openjdk.java.net/projects/code-tools/jol/'
//val jol = settingKey[scala.collection.Seq[sbt.ModuleID]]("jol libraries")
//jol := Seq("org.openjdk.jol" % "jol-core" % "0.14", "org.openjdk.jol" % "jol-cli" % "0.14")
//libraryDependencies ++= jol.value

//mainClass in Compile := Some("org.openjdk.jol.Main")

mappings in Universal ++= (baseDirectory.value / "conf" * "*" get) map (x => x -> ("conf/" + x.getName))

//or woshilaiceshide.sserver.EchoHttpServer
mainClass in (Test, run) := Some("woshilaiceshide.sserver.test.SampleHttpServer")

