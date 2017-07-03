import typeformation.cf._
import typeformation.cf.resources._
import typeformation.cf.syntax._
import PseudoParameter._

import scala.concurrent.ExecutionContext.Implicits.global

object Main extends App {
  object Mappings {
    val Endpoint = "Endpoint"
    val HostedZoneId = "HostedZoneId"

    val regionToEndpointZoneId = {
      def m(s1: String, s2: String) = Map(Mappings.Endpoint -> s1, Mappings.HostedZoneId -> s2)

      Template.Mapping("Region2S3Website", Map(
        "us-east-2" -> m("s3-website.us-east-2.amazonaws.com", "Z2O1EMRO9K5GLX"),
        "us-east-1" -> m("s3-website-us-east-1.amazonaws.com", "Z3AQBSTGFYJSTF"),
        "us-west-1" -> m("s3-website-us-west-1.amazonaws.com", "Z2F56UZL2M1ACD"),
        "us-west-2" -> m("s3-website-us-west-2.amazonaws.com", "Z3BJ6K6RIION7M"),
        "ca-central-1" -> m("s3-website.ca-central-1.amazonaws.com", "Z1QDHH18159H29"),
        "ap-south-1" -> m("s3-website.ap-south-1.amazonaws.com", "Z11RGJOFQNVJUP"),
        "ap-northeast-2" -> m("s3-website.ap-northeast-2.amazonaws.com", "Z3W03O7B5YMIYP"),
        "ap-southeast-1" -> m("s3-website-ap-southeast-1.amazonaws.com", "Z3O0J2DXBE1FTB"),
        "ap-southeast-2" -> m("s3-website-ap-southeast-2.amazonaws.com", "Z1WCIGYICN2BYD"),
        "ap-northeast-1" -> m("s3-website-ap-northeast-1.amazonaws.com", "Z2M4EHUR26P7"),
        "eu-central-1" -> m("s3-website.eu-central-1.amazonaws.com", "Z21DNDUVLTQW6Q"),
        "eu-west-1" -> m("s3-website-eu-west-1.amazonaws.com", "Z1BKCTXD74EZPE"),
        "eu-west-2" -> m("s3-website.eu-west-2.amazonaws.com", "Z3GKZC51ZF0DB4"),
        "sa-east-1" -> m("s3-website-sa-east-1.amazonaws.com", "Z7KQH4QJS55SO")
      ))
    }
  }

  val hostedZoneParam = Parameter.Str(
      logicalId = "HostedZone",
      Description = Some("The DNS name of an existing Amazon Route 53 hosted zone"),
      AllowedPattern = Some("(?!-)[a-zA-Z0-9-.]{1,63}(?<!-)"),
      ConstraintDescription = Some("must be a valid DNS zone name.")
    )

  object Resources {
    val websiteBucket = AWSS3Bucket(
      logicalId = "S3BucketForWebsiteContent",
      BucketName = Some(hostedZoneParam.ref),
      AccessControl = "PublicRead",
      WebsiteConfiguration = Some(AWSS3Bucket.WebsiteConfiguration(
        IndexDocument = "index.html",
        ErrorDocument = "error.html"
      ))
    )

    val domainName =
        fnJoin("", (websiteBucket.ref, fnFindInMap(Mappings.regionToEndpointZoneId, Region.ref, Mappings.Endpoint)))

    val originId = "origin-id"
    val origins = List(AWSCloudFrontDistribution.Origin(
      Id = originId,
      DomainName = domainName,
      CustomOriginConfig = Some(AWSCloudFrontDistribution.CustomOriginConfig(
        OriginProtocolPolicy = "http-only",
        HTTPPort = 80,
        HTTPSPort = 443))))

    val websiteCDN = {
      val chunks =
        (StackName.ref, AccountId.ref, lit("."), Region.ref, lit("."), hostedZoneParam.ref)

      AWSCloudFrontDistribution(
        logicalId = "WebsiteCDN",
        DistributionConfig = AWSCloudFrontDistribution.DistributionConfig(
          Comment = "CDN for S3-backed website",
          Aliases = Some(List(fnJoin("", chunks))),
          Enabled = true,
          DefaultCacheBehavior = AWSCloudFrontDistribution.DefaultCacheBehavior(
            ForwardedValues = AWSCloudFrontDistribution.ForwardedValues(QueryString = true),
            TargetOriginId = originId,
            ViewerProtocolPolicy = "allow-all"
          ),
          DefaultRootObject = "index.all",
          Origins = origins
        )
      )
    }

    val websiteDns = {
      val hostedZoneName = fnJoin("", (hostedZoneParam.ref, lit(".")))

      AWSRoute53RecordSetGroup(
        logicalId = "websiteDNSName",
        HostedZoneName = Some(hostedZoneName),
        RecordSets = Some(List(AWSRoute53RecordSetGroup.RecordSet(
          Type = "A",
          Name = hostedZoneName,
          AliasTarget = Some(AWSRoute53RecordSetGroup.AliasTarget(
            HostedZoneId = fnFindInMap(Mappings.regionToEndpointZoneId, Region.ref, Mappings.HostedZoneId),
            DNSName = fnFindInMap(Mappings.regionToEndpointZoneId, Region.ref, Mappings.Endpoint)
          ))
        )))
      )
    }

    val all: List[Resource] = List(websiteBucket, websiteDns)
  }

  val websiteUrlOutput = Output(
    logicalId = "WebsiteURL",
    Value = fnGetAtt(Resources.websiteBucket, "WebsiteURL"),
    Description = Some("URL of website hosted on S3")
  )

  val template = Template(
    Mappings = List(Mappings.regionToEndpointZoneId),
    Parameters = List(hostedZoneParam),
    Resources = Resources.all,
    Outputs = List(websiteUrlOutput)
  )

  val runner = new CFRunner
  val params = Map(hostedZoneParam.logicalId -> "afio.re")

  println("Running stack!")

  runner.createStack("website", template, params).onComplete { res =>
    println(s"Done: $res")
  }
}
