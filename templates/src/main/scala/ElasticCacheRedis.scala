import java.nio.file.{Files, Paths}

import typeformation.cf._
import typeformation.cf.resources._
import typeformation.cf.init._
import typeformation.cf.syntax._
import typeformation.cf.iam._
import typeformation.cf.iam.syntax._
import io.circe.syntax._
import Encoding._
import typeformation.cf.PseudoParameter.Region

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

    val httpBinJarUrl = Parameter.Str(
      logicalId = "HttpbinJarUrl",
      Description = Some("URL where the httpbin Jar file might be downloaded")
    )

    val all = List(clusterType, instanceType, keyName, sshLocation, httpBinJarUrl)
  }

  object Policies {
    val webApp = Policy(
      Statement = List(Statement(
        Effect = Effect.Allow,
        Action = Action("sts:AssumeRole"),
        Resource = Nil,
        Principal = Some(
          Principal.Service(
            fnFindInMap(Mappings.Region2Principal, Region.ref,  "EC2Principal")
          )
        )
    )))

    val webAppRole = Policy(
      Statement = List(Statement(
      Effect = Effect.Allow,
      Action = Action("elasticache:DescribeCacheClusters"),
      Resource = List(Arn("*")),
    )))
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
      AssumeRolePolicyDocument = Policies.webApp.asJson,
      Path = "/"
    )

    val webAppInstanceProfile = AWSIAMInstanceProfile(
      logicalId = "WebAppInstanceProfile",
      Roles = List(webAppRole.ref)
    )

    val webAppRolePolicy = AWSIAMPolicy(
      logicalId = "webAppRolePolicy",
      PolicyDocument = Policies.webAppRole.asJson,
      PolicyName = webAppRole.logicalId,
      Roles = Some(List(
        webAppRole.ref
      ))
    )

    val webAppSecurityGroup = AWSEC2SecurityGroup(
      logicalId ="WebAppSecurityGroup",
      GroupDescription = "Enable SSH and HTTP access",
      SecurityGroupIngress = Some(List(
        AWSEC2SecurityGroup.Ingress(
          CidrIp = Some(Params.sshLocation.ref),
          IpProtocol = "tcp",
          FromPort = 22,
          ToPort = 22
        ),
        AWSEC2SecurityGroup.Ingress(
          CidrIp = "0.0.0.0/0",
          IpProtocol = "tcp",
          FromPort = 8080,
          ToPort = 8080
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
      val logicalId = "WebAppInstance"
      val configSetId = "config"
      val ec2UserHome = "/home/ec2-user"
      val serviceName = "httpbin"
      val artefactName = s"$serviceName.jar"
      val artefactPath = Seq(ec2UserHome, artefactName).mkString("/")

      val httpBinUpstart = fnSub"""description "$serviceName service"

start on runlevel [3]
stop on shutdown

exec sudo -u ec2-user AWS_REGION=${PseudoParameter.Region} AWS_CACHE_CLUSTER_ID=$redisCluster java -jar $artefactPath > $ec2UserHome/$serviceName.log 2>&1"""

      val cfnInitCall = fnJoin"""#!/bin/bash -v
/opt/aws/bin/cfn-init --stack ${PseudoParameter.StackId.ref} --region ${PseudoParameter.Region.ref} --resource $logicalId --configsets $configSetId
        """

      AWSEC2Instance(
        logicalId = logicalId,
        IamInstanceProfile = Some(webAppInstanceProfile.ref),
        ImageId = imageId,
        InstanceType = Some(Params.instanceType.ref),
        KeyName = Some(Params.keyName.ref),
        SecurityGroupIds = Some(List(
          webAppSecurityGroup.ref
        )),
        Metadata = Some(
          Init(Init.Config(
            logicalId = configSetId,
            packages = List(Package("java-1.8.0-openjdk", yum)),
            commands = List(
              Command("01-remove-jdk-1.7", "yum -y remove java-1.7.0-openjdk"),
              Command("02-start-httpbin", s"initctl start $serviceName")
            ),
            files = List(
              File.FromUrl(artefactPath, Params.httpBinJarUrl.ref),
              File.FromString(s"/etc/init/$serviceName.conf", httpBinUpstart)
            )
          )).asJson
        ),
        UserData = Some(fnBase64(cfnInitCall))
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