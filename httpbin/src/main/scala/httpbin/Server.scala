package httpbin

import cats.effect.IO
import com.redis.RedisClient

import scala.util.Properties.{envOrElse, envOrNone}
import fs2.Stream
import org.http4s.util.StreamApp
import org.http4s.server.blaze.BlazeBuilder
import org.slf4j.LoggerFactory
import collection.JavaConverters._
import scala.concurrent.ExecutionContext

object Server extends StreamApp[IO] {
  val port : Int              = envOrNone("HTTP_PORT") map (_.toInt) getOrElse 8080
  val ip   : String           = "0.0.0.0"
  val ec   : ExecutionContext = scala.concurrent.ExecutionContext.global
  val httpBin = new HttpBin(redisClient)

  private val logger = LoggerFactory.getLogger(this.getClass)

  override def stream(args: List[String], requestShutdown: IO[Unit]): Stream[IO, StreamApp.ExitCode] =
    BlazeBuilder[IO]
      .bindHttp(port, ip)
      .mountService(httpBin.service)
      .withExecutionContext(ec)
      .serve

  private def redisClient: RedisClient =
    new RedisClient(envOrElse("REDIS_HOST", "localhost"), envOrElse("REDIS_PORT", "6379").toInt)
}
