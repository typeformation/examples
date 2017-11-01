import CFRunner._
import cats.Eval
import cats.effect.IO
import io.circe.Printer
import io.circe.syntax._
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudformation.CloudFormationAsyncClient
import software.amazon.awssdk.services.cloudformation.model.{
  CreateStackRequest,
  ListStacksRequest,
  OnFailure,
  Parameter,
  StackStatus => AWSStackStatus
}
import typeformation.cf.Encoding._
import typeformation.cf.Template

import scala.compat.java8.FutureConverters._
import scala.concurrent.ExecutionContext
import scala.collection.JavaConverters._

trait CFRunner {
  def createStack(name: String, template: Template)(
      implicit ec: ExecutionContext): IO[Stack.Id]

  def listStacks(filterStatus: Stack.Status)(
      implicit ec: ExecutionContext): IO[List[Stack]]
}

object CFRunner {
  case class Stack(id: Stack.Id, name: String, status: Stack.Status)

  object Stack {
    case class Id(override val toString: String)
    sealed trait Status
    object Status {

      def fromString(s: String): Status = s match {
        case "CREATE_COMPLETE"    => CREATE_COMPLETE
        case "CREATE_FAILED"      => CREATE_FAILED
        case "CREATE_IN_PROGRESS" => CREATE_IN_PROGRESS
        case other                => Unknown(other)
      }

      case object CREATE_IN_PROGRESS                    extends Status
      case object CREATE_FAILED                         extends Status
      case object CREATE_COMPLETE                       extends Status
      case class Unknown(override val toString: String) extends Status
    }
  }

  val default = new CFRunner {
    private val jsonPrinter = Printer.spaces2.copy(dropNullKeys = true)

    def newClient =
      CloudFormationAsyncClient.builder().region(Region.EU_WEST_1).build()

    override def createStack(name: String, template: Template)(
        implicit ec: ExecutionContext): IO[Stack.Id] = {

      val parameters = Map
        .empty[String, String]
        .map {
          case (k, v) =>
            Parameter.builder().parameterKey(k).parameterValue(v).build()
        }
        .toSeq

      val templateBody = template.asJson.pretty(jsonPrinter)

      val req =
        CreateStackRequest
          .builder()
          .stackName(name)
          .parameters(parameters: _*)
          .templateBody(templateBody)
          .onFailure(OnFailure.DO_NOTHING)
          .timeoutInMinutes(20)
          .build()

      val client       = newClient
      val issueRequest = Eval.always(client.createStack(req).toScala)

      for {
        result <- IO.fromFuture(issueRequest).attempt
        _      <- IO(client.close())
        stackId <- result.fold(IO.raiseError,
                               resp => IO.pure(Stack.Id(resp.stackId())))
      } yield stackId
    }

    override def listStacks(filter: Stack.Status)(
        implicit ec: ExecutionContext): IO[List[Stack]] = {
      type Token = String
      import Stack._

      val client = newClient

      def go(token: Option[Token], acc: List[Stack]): IO[List[Stack]] = {
        val builder =
          ListStacksRequest.builder().stackStatusFilters(filter.toString)
        val req          = token.fold(builder)(builder.nextToken).build()
        val issueRequest = Eval.always(client.listStacks(req).toScala)

        for {
          resp <- IO.fromFuture(issueRequest)
          listing = resp.stackSummaries().asScala.toList
          stacks = acc ++ listing.map { s =>
            Stack(Id(s.stackId()),
                  s.stackName(),
                  Status.fromString(s.stackStatus()))
          }

          allStacks <- Option(resp.nextToken)
            .fold(IO.pure(stacks))(token => go(Some(token), stacks))

        } yield allStacks
      }

      go(None, Nil)
    }
  }
}
