package httpbin

import java.util.concurrent.{ExecutorService, Executors}

import software.amazon.awssdk.services.elasticache.ElastiCacheClient
import com.redis._

import scala.util.Properties.envOrNone
import fs2.{Stream, Task}
import org.http4s.util.StreamApp
import org.http4s.server.blaze.BlazeBuilder
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.InstanceProfileCredentialsProvider
import software.amazon.awssdk.client.builder.ClientHttpConfiguration
import software.amazon.awssdk.http.apache.ApacheSdkHttpClientFactory
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.elasticache.model.DescribeCacheClustersRequest

import collection.JavaConverters._

object Server extends StreamApp {
  val port : Int              = envOrNone("HTTP_PORT") map (_.toInt) getOrElse 8080
  val ip   : String           = "0.0.0.0"
  val pool : ExecutorService  = Executors.newCachedThreadPool()
  val httpBin = new HttpBin(redisClient)

  private val logger = LoggerFactory.getLogger(this.getClass)

  override def stream(args: List[String]): Stream[Task, Nothing] =
    BlazeBuilder
      .bindHttp(port, ip)
      .mountService(httpBin.service)
      .withServiceExecutor(pool)
      .serve

  private def redisClient: RedisClient = {
    envOrNone("AWS_REGION").zip(envOrNone("AWS_CACHE_CLUSTER_ID")).headOption.flatMap { case (region, clusterId) =>
      val awsRegion = Region.of(region)
      val httpConfig = ClientHttpConfiguration.builder().httpClientFactory(ApacheSdkHttpClientFactory.builder().build()).build()

      //use IAM credentials associated to the ec2 instance metadata
      val creds = new InstanceProfileCredentialsProvider()

      val elastiCacheClient =
        ElastiCacheClient.builder()
          .region(awsRegion)
          .credentialsProvider(creds)
          .httpConfiguration(httpConfig)
          .build()

      val req =
        DescribeCacheClustersRequest.builder()
          .cacheClusterId(clusterId)
          .showCacheNodeInfo(true)
          .build()

      val result = elastiCacheClient.describeCacheClusters(req)
      val cluster = result.cacheClusters().asScala.find(_.cacheClusterId() == clusterId)
      val cacheNodeEndpoint = cluster.flatMap(_.cacheNodes().asScala.headOption).map(_.endpoint())

      cacheNodeEndpoint.map { ep =>
        new RedisClient(ep.address(), ep.port())
      }
    }.getOrElse(new RedisClient("localhost", 6379))
  }
}
