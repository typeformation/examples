name := "cf-examples"

version := "1.0"

scalaVersion in ThisBuild := "2.12.2"

val Http4sVersion = "0.18.0-M4"
val AwsSdkVersion = "2.0.0-preview-1"

val httpBin =
  (project in file("httpbin"))
    .settings(
        test in assembly := {},
        mainClass in assembly := Some("httpbin.Server"),
        assemblyMergeStrategy in assembly := {
          case PathList("META-INF", xs @ _*) => MergeStrategy.discard
          case x => MergeStrategy.first
        },

        libraryDependencies ++= Seq(
          "software.amazon.awssdk" % "elasticache" % AwsSdkVersion,
          "net.debasishg" %% "redisclient" % "3.4",
          "org.http4s" %% "http4s-blaze-server" % Http4sVersion,
          "org.http4s" %% "http4s-dsl" % Http4sVersion,
          "ch.qos.logback" % "logback-classic" % "1.2.1",
           "org.scalatest" %% "scalatest" % "3.0.1" % "test"
        )
    )

val root =
  (project in file(".")).aggregate(httpBin)
