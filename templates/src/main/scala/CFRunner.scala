import io.circe.Printer
import io.circe.syntax._
import software.amazon.awssdk.regions.Region
import typeformation.cf.{Encoding, Template}
import typeformation.cf.Encoding._

import scala.compat.java8.FutureConverters._
import software.amazon.awssdk.services.cloudformation.CloudFormationAsyncClient
import software.amazon.awssdk.services.cloudformation.model.{CreateStackRequest, OnFailure, Parameter, UpdateStackRequest}

import scala.concurrent.{ExecutionContext, Future}

class CFRunner {
  private val client =
    CloudFormationAsyncClient.
      builder().region(Region.EU_WEST_1).
      build()

  def createStack(stackName: String, template: Template, params: Map[String, String])
                 (implicit ec: ExecutionContext): Future[Unit] = {

    val parameters = params.map { case (k, v) =>
      Parameter.builder().parameterKey(k).parameterValue(v).build()
    }.toSeq

    val templateBody = template.asJson.pretty(Printer.spaces2.copy(dropNullKeys = true))

    //val req =
    //  CreateStackRequest.builder()
    //    .stackName(stackName)
    //    .parameters(parameters: _*)
    //    .templateBody(templateBody)
    //    .onFailure(OnFailure.DO_NOTHING)
    //    .timeoutInMinutes(20)
    //    .build()

    //val future = client.createStack(req).toScala.map(resp =>
    //  println("Stack response:", resp)
    //)

    //future.onComplete { _ =>
    //  println("Closing AWS client")
    //  client.close()
    //}
    //future
    Future(println(templateBody))
  }
}
