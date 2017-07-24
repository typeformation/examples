name := "cf-examples"

version := "1.0"

scalaVersion in ThisBuild := "2.12.2"

val Http4sVersion = "0.17.0-M2"
val AwsSdkVersion = "2.0.0-preview-1"

val templates =
  (project in file("templates"))
    .settings(
      libraryDependencies ++= Seq(
        "typeformation" %% "resources" % "0.1-SNAPSHOT",
        "software.amazon.awssdk" % "cloudformation" % "2.0.0-preview-1",
        "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0"
      )
    )

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
          "software.amazon.awssdk" % "aws-http-client-apache" % AwsSdkVersion,
          "net.debasishg" %% "redisclient" % "3.4",
          "org.http4s" %% "http4s-blaze-server" % Http4sVersion,
          "org.http4s" %% "http4s-circe" % Http4sVersion,
          "org.http4s" %% "http4s-dsl" % Http4sVersion,
          "ch.qos.logback" % "logback-classic" % "1.2.1"
        )
    )

val root =
  (project in file(".")).aggregate(templates, httpBin)