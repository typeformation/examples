name := "cf-examples"

version := "1.0"

scalaVersion := "2.12.2"

libraryDependencies ++= Seq(
  "typeformation" %% "resources" % "0.1-SNAPSHOT",
  "software.amazon.awssdk" % "cloudformation" % "2.0.0-preview-1",
  "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0"
)