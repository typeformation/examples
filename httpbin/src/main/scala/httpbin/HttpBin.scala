package httpbin

import java.util.UUID

import org.http4s._
import org.http4s.dsl._
import com.redis._
import org.http4s.headers.`Content-Type`

class HttpBin(redisClient: RedisClient) {
  val service = HttpService {
    case req @ POST -> Root / "bin" =>
      val uid = UUID.randomUUID()

      req.bodyAsText.map { body =>
        redisClient.set(s"bin/$uid", body)
        req.contentType.foreach(ct => redisClient.set(s"bin/$uid/content-type", ct.toRaw.value))
        Created(s"$uid\n")
      }.runLog.unsafeRun().head

    case GET -> Root / "bin" / uid =>
      val entity = for {
        ct <- redisClient.get(s"bin/$uid/content-type")
        body <- redisClient.get(s"bin/$uid")
        contentType <- `Content-Type`.parse(ct).toOption
      } yield Ok(body).withContentType(Some(contentType))

      entity getOrElse NotFound()
  }
}
