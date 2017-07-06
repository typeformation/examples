import java.nio.file.{Files, Paths}

import typeformation.cf._
import typeformation.cf.resources._
import typeformation.cf.syntax._
import io.circe.syntax._
import Encoding._

object ElasticCacheRedis extends App {

  object Mappings {
    val Arch = "Arch"
    private val archPV64 = Map(Arch -> "PV64")
    private val archHVM64 = Map(Arch -> "HVM64")

    val AWSInstanceType2Arch = Template.Mapping("AwsInstanceType2Arch", Map(
      "t1.micro" -> archPV64,
      "t2.micro" -> archHVM64,
      "t2.small" -> archHVM64
    ))

    val AWSRegionArch2AMI = Template.Mapping("AwsRegionArch2AMI", Map(
      "eu-west-1" -> Map(
        "HVM64" -> "ami-e4d18e93",
        "HVMG2" -> "ami-72a9f105",
        "PV64" -> "ami-d6d18ea1"
      )
    ))

    val Region2Principal = Template.Mapping("Region2Principal", Map(
      "eu-west-1" -> Map(
        "EC2Principal" -> "ec2.amazonaws.com",
        "OpsWorksPrincipal" -> "opsworks.amazonaws.com"
      )
    ))

    val all = List(AWSInstanceType2Arch, AWSRegionArch2AMI, Region2Principal)
  }

  object Params {
    val clusterType = Parameter.Str(
      logicalId = "ClusterNodeType",
      AllowedValues = Some(Set(
        "cache.m1.small",
        "cache.m1.large",
        "cache.m1.xlarge",
        "cache.m2.xlarge",
        "cache.m2.2xlarge",
        "cache.m2.4xlarge",
        "cache.c1.xlarge"
      )),
      ConstraintDescription = Some("must select a valid Cache Node type."),
      Default= Some("cache.m1.small"),
      Description= Some("The compute and memory capacity of the nodes in the Redis Cluster")
    )

    val instanceType = Parameter.Str(
      logicalId = "instanceType",
      AllowedValues = Some(Set(
        "t1.micro",
        "t2.micro",
        "t2.small"
      )),
      ConstraintDescription = Some("must be a valid EC2 instance type."),
      Description = Some("WebServer EC2 instance type"),
      Default = Some("t2.micro")
    )

    val keyName = Parameter.Aws(
      logicalId = "keyName",
      awsType = Parameter.AwsParamType.`AWS::EC2::KeyPair::KeyName`,
      ConstraintDescription = Some("must be the name of an existing EC2 KeyPair")
    )

    val sshLocation = Parameter.Str(
      logicalId = "sshLocation",
      AllowedPattern = Some("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})/(\d{1,2})"""),
      ConstraintDescription = Some("must be a valid IP CIDR range of the form x.x.x.x/x."),
      Description = Some("must be a valid IP CIDR range of the form x.x.x.x/x."),
      MaxLength = Some(18),
      MinLength = Some(9)
    )

    val all = List(clusterType, instanceType, keyName, sshLocation)
  }

  object Policies {
    val webApp = io.circe.parser.parse(
      s"""
        |{
        |   "Statement": [
        |      {
        |          "Action": [
        |              "sts:AssumeRole"
        |          ],
        |          "Effect": "Allow",
        |          "Principal": {
        |              "Service": [
        |                  {
        |                      "Fn::FindInMap": [
        |                          "${Mappings.Region2Principal.logicalId}",
        |                          {
        |                              "Ref": "AWS::Region"
        |                          },
        |                          "EC2Principal"
        |                      ]
        |                  }
        |              ]
        |          }
        |      }
        |   ]
        | }
      """.stripMargin).getOrElse(sys.error(s"Cannot parse IAM policy for WebAppRole"))

    val webAppRole = io.circe.parser.parse(
      """
        |{
        |  "Statement": [
        |      {
        |          "Action": [
        |              "elasticache:DescribeCacheClusters"
        |          ],
        |          "Effect": "Allow",
        |          "Resource": [
        |              "*"
        |          ]
        |      }
        |  ]
        |}
      """.stripMargin).getOrElse(sys.error("cannot parse policy for webApp role"))
  }

  object Resources {

    val redisSecurityGroup = AWSElastiCacheSecurityGroup(
      logicalId = "RedisSecurityGroup",
      Description = "Lock down the cluster"
    )

    val redisCluster = AWSElastiCacheCacheCluster(
      logicalId = "RedisCluster",
      CacheNodeType = Params.clusterType.ref,
      Engine = "redis",
      NumCacheNodes = 1,
      CacheSecurityGroupNames = Some(List(
        redisSecurityGroup.ref
      ))
    )

    val webAppRole = AWSIAMRole(
      logicalId = "WebAppRole" ,
      AssumeRolePolicyDocument = Policies.webApp,
      Path = "/"
    )

    val webAppInstanceProfile = AWSIAMInstanceProfile(
      logicalId = "WebAppInstanceProfile",
      Roles = List(webAppRole.ref)
    )

    val webAppRolePolicy = AWSIAMPolicy(
      logicalId = "webAppRolePolicy",
      PolicyDocument = Policies.webAppRole,
      PolicyName = webAppRole.logicalId,
      Roles = Some(List(
        webAppRole.ref
      ))
    )

    val webAppSecurityGroup = AWSEC2SecurityGroup(
      logicalId ="WebAppSecurityGroup",
      GroupDescription = "Enable SSH and HTTP access",
      SecurityGroupIngress = Some(List(
        AWSEC2SecurityGroup.Rule(
          CidrIp = Some(Params.sshLocation.ref),
          IpProtocol = "tcp",
          FromPort = 22,
          ToPort = 22
        ),
        AWSEC2SecurityGroup.Rule(
          CidrIp = "0.0.0.0/0",
          IpProtocol = "tcp",
          FromPort = 80,
          ToPort = 80
        ))
      )
    )

    val redisSecurityGroupIngress = AWSElastiCacheSecurityGroupIngress(
      logicalId = "RedisSecurityGroupIngress",
      CacheSecurityGroupName = redisSecurityGroup.ref,
      EC2SecurityGroupName = webAppSecurityGroup.ref
    )

    val webAppInstance = {
      import Mappings.{AWSRegionArch2AMI, AWSInstanceType2Arch}

      val arch = fnFindInMap(AWSInstanceType2Arch, Params.instanceType.ref, Mappings.Arch)
      val imageId = fnFindInMap(AWSRegionArch2AMI, PseudoParameter.Region.ref, arch)

      AWSEC2Instance(
        logicalId = "WebAppInstance",
        IamInstanceProfile = Some(webAppInstanceProfile.ref),
        ImageId = imageId,
        InstanceType = Some(Params.instanceType.ref),
        KeyName = Some(Params.keyName.ref),
        SecurityGroupIds = Some(List(
          webAppSecurityGroup.ref
        ))
      )
    }

    val all = List(
      redisCluster, redisSecurityGroup, redisSecurityGroupIngress,
      webAppRole, webAppInstanceProfile, webAppRolePolicy,
      webAppSecurityGroup, webAppInstance)
  }

  object Outputs {
    val all = {
      val appDns: CfExp[String] = fnGetAtt(Resources.webAppInstance, "PublicDnsName")
      List(
        Output(
          logicalId = "WebAppUrl",
          Description = Some("Application URL"),
          Value = fnJoin("", (lit("http://"), appDns))
        )
      )
    }
  }

  val template = Template(
    Mappings = Mappings.all,
    Parameters = Params.all,
    Resources = Resources.all,
    Outputs = Outputs.all
  )

  val printer = io.circe.Printer.spaces2.copy(dropNullKeys = true)
  val outputPath = Paths.get("/tmp/redis-cluster.json")

  Files.write(outputPath, printer.pretty(template.asJson).getBytes())

  //discover redis cluster:
  // aws elasticache describe-cache-clusters --cache-cluster-id red-re-1ts0qdti6d76h --show-cache-node-info --region eu-west-1
}