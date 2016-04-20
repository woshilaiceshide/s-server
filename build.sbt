organization := "woshilaiceshide"

name := "s-server"

version := "1.2-SNAPSHOT"

description := "Some Small Servers written in Scala, including a nio server and a small httpd, which also supports websocket(v13 only)."

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))

publishMavenStyle := true

enablePlugins(BintrayPlugin)

pomIncludeRepository  := {_ => false}

bintrayRepository := "maven"

bintrayOrganization := None

bintrayVcsUrl := Some(s"git@github.com:woshilaiceshide/${name.value}.git")

bintrayReleaseOnPublish in ThisBuild := false

compileOrder in Compile := CompileOrder.Mixed

transitiveClassifiers := Seq("sources")

EclipseKeys.withSource := true

scalaVersion := "2.11.8"

scalacOptions := Seq("-unchecked", "-deprecation","-optimise", "-encoding", "utf8", "-Yno-adapted-args")

javacOptions ++= Seq("-Xlint:deprecation", "-Xlint:unchecked", "-source", "1.7", "-target", "1.7", "-g:vars")

retrieveManaged := false

enablePlugins(JavaAppPackaging)

net.virtualvoid.sbt.graph.Plugin.graphSettings

unmanagedSourceDirectories in Compile <+= baseDirectory( _ / "src" / "java" )

unmanagedSourceDirectories in Compile <+= baseDirectory( _ / "src" / "scala" )

libraryDependencies ++= {
  val akkaV = "2.3.15"
  val sprayV = "1.3.3"
  Seq(
  //"io.spray"            %%   "spray-routing" % sprayV,
  //"io.spray"            %%   "spray-caching" % sprayV,
    "io.spray"            %%   "spray-can"     % sprayV,
  //"io.spray"            %%   "spray-client"  % sprayV,
    "io.spray"            %%   "spray-http"    % sprayV,
  //"io.spray"            %%   "spray-httpx"   % sprayV,
    "io.spray"            %%   "spray-io"      % sprayV,
  //"io.spray"            %%   "spray-testkit" % sprayV,
    "io.spray"            %%   "spray-util"    % sprayV,
  //"com.typesafe.akka"   %%  "akka-testkit"   % akkaV,
    "com.typesafe.akka"   %%  "akka-actor"     % akkaV
  //"com.typesafe.akka"   %%  "akka-slf4j"     % akkaV
  )
}

mappings in Universal ++= (baseDirectory.value / "conf" * "*" get) map (x => x -> ("conf/" + x.getName))

//mainClass in (Test, run) := Some("woshilaiceshide.sserver.EchoServer")

mainClass in Test := Some("woshilaiceshide.sserver.EchoServer")

