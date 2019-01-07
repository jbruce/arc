package au.com.agl.arc.util

import java.net.URI

import scala.io.Source

import org.scalatest.FunSuite
import org.scalatest.BeforeAndAfter

import org.apache.spark.sql._

import au.com.agl.arc.api.API._
import au.com.agl.arc.api.{Delimited, Delimiter, QuoteCharacter}
import au.com.agl.arc.util.log.LoggerFactory
import au.com.agl.arc.util.ConfigUtils._

import com.typesafe.config._


class ConfigUtilsSuite extends FunSuite with BeforeAndAfter {

  var session: SparkSession = _

  before {
    val spark = SparkSession
                  .builder()
                  .master("local[*]")
                  .appName("Spark ETL Test")
                  .getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")

    session = spark
  }

  after {
    session.stop()
  }

  test("Read simple config") {
    implicit val spark = session

    implicit val logger = LoggerFactory.getLogger(spark.sparkContext.applicationId)
    implicit val arcContext = ARCContext(jobId=None, jobName=None, environment="test", environmentId=None, configUri=None, isStreaming=false, ignoreEnvironments=false)

    val argsMap = collection.mutable.HashMap[String, String]()

    val pipeline = ConfigUtils.parsePipeline(Option("classpath://conf/simple.conf"), argsMap, arcContext)

    val stage = DelimitedExtract(
      name = "file extract",
      cols = Right(Nil),
      outputView = "green_tripdata0_raw",
      input = Right("/data/green_tripdata/0/*.csv"),
      settings = Delimited(Delimiter.Comma, QuoteCharacter.DoubleQuote, true, false),
      authentication = None,
      params = Map.empty,
      persist = false,
      numPartitions = None,
      partitionBy = Nil,
      contiguousIndex = true
    )

    val subDelimitedExtractStage = DelimitedExtract(
      name = "extract data from green_tripdata/1",
      cols = Right(Nil),
      outputView = "green_tripdata1_raw",
      input = Right("/data/green_tripdata/1/*.csv"),
      settings = Delimited(Delimiter.Comma, QuoteCharacter.DoubleQuote, true, false),
      authentication = None,
      params = Map.empty,
      persist = false,
      numPartitions = None,
      partitionBy = Nil,
      contiguousIndex = true
    )

    val schema =
      IntegerColumn(
        id = "f457e562-5c7a-4215-a754-ab749509f3fb",
        name = "vendor_id",
        description = Some("A code indicating the TPEP provider that provided the record."),
        nullable = true,
        nullReplacementValue = None,
        trim = true,
        nullableValues = "" :: "null" :: Nil,
        metadata = None,
        formatters = None) ::
      TimestampColumn(
        id = "d61934ed-e32e-406b-bd18-8d6b7296a8c0",
        name = "lpep_pickup_datetime",
        description = Some("The date and time when the meter was engaged."),
        nullable = true,
        nullReplacementValue = None,
        trim = true,
        nullableValues = "" :: "null" :: Nil,
        timezoneId = "America/New_York",
        formatters = "yyyy-MM-dd HH:mm:ss" :: Nil,
        time = None,
        metadata = None,
        strict = false) :: Nil


    val subTypingTransformStage = TypingTransform(
      name = "apply green_tripdata/1 data types",
      cols = Right(schema),
      inputView = "green_tripdata1_raw",
      outputView = "green_tripdata1",
      params = Map.empty,
      persist=true,
      failMode=None
    )

    val subSQLValidateStage = SQLValidate(
      name = "ensure no errors exist after data typing",
      inputURI = URI.create("classpath://conf/sql/sqlvalidate_errors.sql"),
      sql =
        """|SELECT
           |  SUM(error) = 0
           |  ,TO_JSON(NAMED_STRUCT('count', COUNT(error), 'errors', SUM(error)))
           |FROM (
           |  SELECT
           |    CASE
           |      WHEN SIZE(_errors) > 0 THEN 1
           |      ELSE 0
           |    END AS error
           |  FROM ${table_name}
           |) input_table""".stripMargin,
      sqlParams = Map("table_name" -> "green_tripdata1", "test_integer" -> "1"),
      params = Map.empty
    )

    val expected = ETLPipeline(stage :: subDelimitedExtractStage :: subTypingTransformStage :: subSQLValidateStage :: Nil)

    assert(pipeline === Right(expected))
  }

  // This test loops through the /src/test/resources/docs_resources directory and tries to parse each file as a config
  // the same config files are used (embedded) on the documentation site so this ensures the examples will work.
  test("Read documentation config files") {
    implicit val spark = session
    implicit val logger = LoggerFactory.getLogger(spark.sparkContext.applicationId)
    implicit val arcContext = ARCContext(jobId=None, jobName=None, environment="test", environmentId=None, configUri=None, isStreaming=false, ignoreEnvironments=false)

    val argsMap = collection.mutable.HashMap[String, String]()

    val resourcesDir = getClass.getResource("/docs_resources/").getPath

    for (filename <- TestDataUtils.getListOfFiles(resourcesDir)) {
      val fileContents = Source.fromFile(filename).getLines.mkString("\n")
      val conf = s"""{"stages": [${fileContents.trim}]}"""

      val base = ConfigFactory.load()
      val etlConf = ConfigFactory.parseString(conf, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
      val config = etlConf.withFallback(base)
      var argsMap = collection.mutable.Map[String, String]()
      val pipelineEither = ConfigUtils.readPipeline(config.resolve(), new URI(""), argsMap, arcContext)

      pipelineEither match {
        case Left(errors) => {
          assert(false, s"Error in config ${filename}: ${ConfigUtils.Error.pipelineErrorMsg(errors)}")
        }
        case Right(pipeline) => {
          assert(true)
        }
      }
    }
  }

  test("Test missing keys exception") { 
    implicit val spark = session
    implicit val logger = LoggerFactory.getLogger(spark.sparkContext.applicationId)
    implicit val arcContext = ARCContext(jobId=None, jobName=None, environment="test", environmentId=None, configUri=None, isStreaming=false, ignoreEnvironments=false)

    val conf = """{
      "stages": [
        {
          "type": "DelimitedExtract",
          "name": "file extract",
          "environments": [
            "production",
            "test"
          ]
        }
      ]
    }"""

    val base = ConfigFactory.load()
    val etlConf = ConfigFactory.parseString(conf, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
    val config = etlConf.withFallback(base)
    var argsMap = collection.mutable.Map[String, String]()
    val pipeline = ConfigUtils.readPipeline(config.resolve(), new URI(""), argsMap, arcContext)    

    pipeline match {
      case Left(stageError) => {
        assert(stageError == 
        StageError("file extract",3,List(
            ConfigError("inputURI", "Missing required attribute 'inputURI'.")
            ,ConfigError("outputView", "Missing required attribute 'outputView'.")
          )
        ) :: Nil)
      }
      case Right(_) => assert(false)
    }    
  }

  test("Test rightFlatMap validation") { 
    implicit val spark = session
    implicit val logger = LoggerFactory.getLogger(spark.sparkContext.applicationId)
    implicit val arcContext = ARCContext(jobId=None, jobName=None, environment="test", environmentId=None, configUri=None, isStreaming=false, ignoreEnvironments=false)

    val conf = """{
      "stages": [
        {
          "type": "DelimitedExtract",
          "name": "file extract",
          "environments": [
            "production",
            "test"
          ],
          "inputURI": "hdfs://test/{ab,c{de, fg}",
          "outputView": "output"
        }
      ]
    }"""

    val base = ConfigFactory.load()
    val etlConf = ConfigFactory.parseString(conf, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
    val config = etlConf.withFallback(base)
    var argsMap = collection.mutable.Map[String, String]()
    val pipeline = ConfigUtils.readPipeline(config.resolve(), new URI(""), argsMap, arcContext)    

    pipeline match {
      case Left(stageError) => {
        assert(stageError == 
        StageError("file extract",3,List(
            ConfigError("inputURI", """Invalid value for attribute 'inputURI' on line 10: Unclosed group near index 25
hdfs://test/{ab,c{de, fg}
                         ^.""")
          )
        ) :: Nil)
      }
      case Right(_) => assert(false)
    }     
  }

  test("Test extraneous attributes") { 
    implicit val spark = session
    implicit val logger = LoggerFactory.getLogger(spark.sparkContext.applicationId)
    implicit val arcContext = ARCContext(jobId=None, jobName=None, environment="test", environmentId=None, configUri=None, isStreaming=false, ignoreEnvironments=false)

    val conf = """{
      "stages": [
        {
          "type": "DelimitedExtract",
          "name": "file extract 0",
          "environments": [
            "production",
            "test"
          ],
          "inputView": "input",
          "outputView": "output",
        },        
        {
          "type": "DelimitedExtract",
          "name": "file extract 1",
          "environments": [
            "production",
            "test"
          ],
          "inputView": "input",
          "outputVew": "output",
          "persst": false
        }
      ]
    }"""

    val base = ConfigFactory.load()
    val etlConf = ConfigFactory.parseString(conf, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
    val config = etlConf.withFallback(base)
    var argsMap = collection.mutable.Map[String, String]()
    val pipeline = ConfigUtils.readPipeline(config.resolve(), new URI(""), argsMap, arcContext)    

    pipeline match {
      case Left(stageError) => {
        assert(stageError == 
        StageError("file extract 1",13,List(
            ConfigError("outputView", "Missing required attribute 'outputView'.")
            ,ConfigError("persst", "Invalid attribute 'persst' found on line 22. Perhaps you meant one of: ['persist'].")
            ,ConfigError("outputVew", "Invalid attribute 'outputVew' found on line 21. Perhaps you meant one of: ['outputView'].")
          )
        ) :: Nil)
      }
      case Right(_) => assert(false)
    }    
  }
  
  test("Test invalid validValues") { 
    implicit val spark = session
    implicit val logger = LoggerFactory.getLogger(spark.sparkContext.applicationId)
    implicit val arcContext = ARCContext(jobId=None, jobName=None, environment="test", environmentId=None, configUri=None, isStreaming=false, ignoreEnvironments=false)

    val conf = """{
      "stages": [       
        {
          "type": "DelimitedExtract",
          "name": "file extract",
          "environments": [
            "production",
            "test"
          ],
          "inputView": "input",
          "outputView": "output",
          "delimiter": "abc"
        }
      ]
    }"""

    val base = ConfigFactory.load()
    val etlConf = ConfigFactory.parseString(conf, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
    val config = etlConf.withFallback(base)
    var argsMap = collection.mutable.Map[String, String]()
    val pipeline = ConfigUtils.readPipeline(config.resolve(), new URI(""), argsMap, arcContext)    

    pipeline match {
      case Left(stageError) => {
        assert(stageError == StageError("file extract",3,List(ConfigError("delimiter", "Invalid value found for 'delimiter' on line 12. Valid values are ['Comma','Pipe','DefaultHive']."))) :: Nil)
      }
      case Right(_) => assert(false)
    }
  }  
}
