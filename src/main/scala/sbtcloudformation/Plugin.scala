package sbtcloudformation

import sbt._
import sbt.Keys._
import scala.util.{Failure, Try}
import com.amazonaws.auth.{DefaultAWSCredentialsProviderChain, AWSCredentials}
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient
import com.amazonaws.services.cloudformation.model.{DeleteStackRequest, CreateStackRequest, DescribeStacksRequest, ValidateTemplateRequest}
import scala.collection.convert.WrapAsScala._

object Plugin extends sbt.Plugin {
  lazy val awsCredentialsProvider = new DefaultAWSCredentialsProviderChain()


  object Configurations {
    lazy val Production = config("production")
    lazy val Staging = config("staging")
  }

  object Keys {

    val templatesSourceFolder = settingKey[File]("folder where CloudFormation templates are")
    val awsCredentials = settingKey[AWSCredentials]("AWS credentials")
    val templates = taskKey[Seq[File]]("template sources")
    val validate = taskKey[Seq[File]]("validate templates")
    val startParameters = settingKey[Map[String, String]]("parameters for starting CloudFormation")

    // stack operations
    val stackName = settingKey[String]("stack name")
    val describe = taskKey[Unit]("describe stacks")
    val create = taskKey[String]("create a stack and returns its stackId")
    val delete = taskKey[Unit]("delete a stack")
  }

  import Keys._
  import Configurations._

  val validationSettings = Seq(
    templatesSourceFolder := file("src/main/aws"),
    templates := {
      val templates = templatesSourceFolder.value ** GlobFilter("*.template")
      templates.get
    },
    validate <<= (awsCredentials, templates, streams) map {
      (credentials, files, s) =>

        val client = new AmazonCloudFormationClient(credentials)
        val results: Seq[(File, Try[List[String]])] = files.map(validateTemplate(client, s.log))
        results.foreach {
          tr =>
            tr._2 match {
              case Failure(e) => s.log.error(s"validation of ${tr._1} failed with: \n ${e.getMessage}")
              case _ =>
            }
        }

        if (results.exists(_._2.isFailure)) {
          sys.error("some AWS CloudFormation templates failed to validate!")
        }

        files
    },
    awsCredentials := {
      awsCredentialsProvider.getCredentials
    }
  )

  val operationSettings = makeConfig(Staging) ++ makeConfig(Production)


  def makeConfig(config: Configuration) = Seq(
    stackName in config <<= (normalizedName) {
      normName =>
        s"${normName}-${config.name}"
    },
    describe in config <<= (awsCredentials, stackName in config, streams) map {
      (credentials, stack, s) =>

        val client = new AmazonCloudFormationClient(credentials)
        val request: DescribeStacksRequest = new DescribeStacksRequest()
        request.setStackName(stack)
        val response = client.describeStacks(request)
        response.getStacks.toList.foreach(stack => s.log.info(s"${stack.toString}"))
    },
    create in config <<= (awsCredentials, stackName in config, streams, validate) map {
      (credentials, stack, s, files) =>

        val client = new AmazonCloudFormationClient(credentials)
        val request = new CreateStackRequest
        request.setStackName(stack)
        request.setTemplateBody(IO.read(files.head))

        val result = client.createStack(request)

        s.log.info(s"created stack ${request.getStackName} / ${result.getStackId}")
        result.getStackId
    },
    delete in config <<= (awsCredentials, stackName in config, streams) map {
      (credentials, stack, s) =>

        val client = new AmazonCloudFormationClient(credentials)
        val request = new DeleteStackRequest
        request.setStackName(stack)

        val result = client.deleteStack(request)

        s.log.info(s"deleting stack ${request.getStackName} ")

    }
  )

  val defaultCloudFormationSettings = validationSettings ++ operationSettings

  def validateTemplate(client: AmazonCloudFormationClient, log: Logger)(template: sbt.File): (File, Try[List[String]]) = {
    (template, Try {
      // TODO validate
      val request: ValidateTemplateRequest = new ValidateTemplateRequest()
      request.setTemplateBody(IO.read(template))
      val result = client.validateTemplate(request)
      log.debug(s"result from validating $template : $result")
      log.info(s"validated $template")
      result.getParameters.toList.map(_.getParameterKey)
    })
  }


}
