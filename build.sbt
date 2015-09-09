organization := "woshilaiceshide"

name := "s-server"

version := "1.0"

compileOrder in Compile := CompileOrder.Mixed

transitiveClassifiers := Seq("sources")

EclipseKeys.withSource := true

scalaVersion := "2.11.7"

scalacOptions := Seq("-unchecked", "-deprecation","-optimise", "-encoding", "utf8", "-Yno-adapted-args")

javacOptions ++= Seq("-Xlint:deprecation", "-Xlint:unchecked", "-source", "1.7", "-target", "1.7", "-g:vars")

retrieveManaged := false

enablePlugins(JavaAppPackaging)

net.virtualvoid.sbt.graph.Plugin.graphSettings

unmanagedSourceDirectories in Compile <+= baseDirectory( _ / "src" / "java" )

unmanagedSourceDirectories in Compile <+= baseDirectory( _ / "src" / "scala" )

libraryDependencies ++= {
  val akkaV = "2.3.12"
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

mainClass in Compile := Some("woshilaiceshide.sserver.EchoServer")

