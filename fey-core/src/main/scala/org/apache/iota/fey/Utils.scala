/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.iota.fey

import java.io.{File, FileOutputStream}
import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, Paths}

import ch.qos.logback.classic.{Level, Logger, LoggerContext}
import ch.qos.logback.core.joran.spi.JoranException
import ch.qos.logback.core.util.StatusPrinter
import com.eclipsesource.schema.SchemaType
import com.typesafe.config.{Config, ConfigFactory, ConfigValue}
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.collection.mutable.HashMap
import scala.io.Source

protected object Utils {

  import CONFIG._

  private val log = LoggerFactory.getLogger(this.getClass)

  /**
    * Keeps the loaded clazz in memory
    * JARNAME,[CLASSPATH, CLASS]
    */

  val loadedJars: HashMap[String, (URLClassLoader, Map[String, Class[FeyGenericActor]])]
                = HashMap.empty[String, (URLClassLoader, Map[String, Class[FeyGenericActor]])]

  /**
    * Gets a list of Files in the directory
    *
    * @param stringPath dir path
    * @return Array of files in the directory
    */
  def getFilesInDirectory(stringPath: String): Array[File]= {
    val dir = new File(stringPath)
    if (dir.exists && dir.isDirectory) {
      dir.listFiles()
    }else{
      Array.empty
    }
  }

  /**
    * Loads an actor class from a .jar that inherited from FeyGenericActor
    *
    * @param path path to the .jar (including the name)
    * @param className class path inside the jar
    * @return class of FeyGenericActor
    */
  def loadActorClassFromJar(path: String, className: String, jarName: String):Class[FeyGenericActor] = {

    loadedJars.get(jarName) match {

      case None =>
        log.info(s"Loading Jar: $jarName")
        val urls:Array[URL] = Array(new URL(s"jar:file:$path!/"))
        val cl: URLClassLoader = URLClassLoader.newInstance(urls, getClass.getClassLoader)
        val clazz = cl.loadClass(className)
        log.info(s"Loading Class $className with path $path")
        val feyClazz = clazz.asInstanceOf[Class[FeyGenericActor]]
        log.info(s"$path -> $className")
        loadedJars.put(jarName, (cl, Map(className -> feyClazz)))
        feyClazz

      case Some(loadedJar) =>
        loadedJar._2.get(className) match {
          case None =>
            log.info(s"Loading Class $className with path $path")
            val clazz = loadedJar._1.loadClass(className)
            val feyClazz = clazz.asInstanceOf[Class[FeyGenericActor]]
            loadedJars.put(jarName, (loadedJar._1, Map(className -> feyClazz) ++ loadedJar._2))
            feyClazz
          case Some(clazz) =>
            clazz
        }
    }

  }

  /**
    * Loads a JSON object from a file
    *
    * @param file
    * @return JsValue of the file
    */
  def loadJsonFromFile(file: File): Option[JsValue] = {
    try{
      val stringJson = Source.fromFile(file).getLines.mkString
      Option(Json.parse(stringJson))
    }catch{
      case e: Exception =>
        log.error("Could not parse JSON", e)
        None
    }
  }

  def renameProcessedFile(file: File, extension: String): Unit = {
    if(CHEKPOINT_ENABLED) {
      file.renameTo(new File(s"${file.getAbsoluteFile}.$extension"))
    }
  }

  /**
    * Saves the Orchestration JSON to a tmp directory so Fey can recovery in case it stops or fails
    *
    * @param orchestrationID
    * @param delete
    * @return
    */
  def updateOrchestrationState(orchestrationID: String, delete: Boolean = false) : Unit = {
    if (CHEKPOINT_ENABLED) {
      FEY_CACHE.activeOrchestrations.get(orchestrationID) match {
        case None =>
          if (!delete) {
            log.warn(s"Could not save state for Orchestration ${orchestrationID}. It is not active on Fey.")
          }
          else {
            val file = new File(s"$CHECKPOINT_DIR/${orchestrationID}.json")
            if (!file.createNewFile()) {
              log.info(s"DELETING FROM CHECKPOINT ${orchestrationID}")
              file.delete()
              log.info(s"DELETED FROM CHECKPOINT ${orchestrationID}")
            }
            ORCHESTRATION_CACHE.orchestration_metadata.remove(orchestrationID)
            ORCHESTRATION_CACHE.orchestration_globals.remove(orchestrationID)
            ORCHESTRATION_CACHE.orchestration_name.remove(orchestrationID)
          }
        case Some(orch) =>
          ORCHESTRATION_CACHE.orchestration_metadata.get(orchestrationID) match {
            case None => log.warn(s"Could not save state for Orchestration ${orchestrationID}. No metadata defined.")
            case Some(metadata) =>
              try {
                val ensembleJSON = metadata.map(ensenble => ensenble._2)
                val name: String = ORCHESTRATION_CACHE.orchestration_name.getOrElse(orchestrationID, "NOT SAVED")
                val globals = ORCHESTRATION_CACHE.orchestration_globals.getOrElse(orchestrationID, HashMap.empty).map(global => global._2)

                val orchestrationSpec = Json.obj(JSON_PATH.GUID -> orchestrationID,
                  JSON_PATH.COMMAND -> "RECREATE",
                  JSON_PATH.ORCHESTRATION_NAME -> name,
                  JSON_PATH.ORCHESTRATION_TIMESTAMP -> System.currentTimeMillis.toString,
                  JSON_PATH.GLOBAL_PERFORMERS -> globals,
                  JSON_PATH.ENSEMBLES -> ensembleJSON)

                saveCheckpointFile(Json.stringify(orchestrationSpec), orchestrationID)

                log.info(s"Orchestration ${orchestrationID} saved.")
              } catch {
                case e: Exception => log.error("Could not manage checkpoint", e)
              }
          }
      }
    }else{
      log.debug("Checkpoint not enabled")
    }
  }

  def saveCheckpointFile(json: String, id: String): Unit ={
    val file = new File(s"$CHECKPOINT_DIR/${id}.json")
    file.getParentFile().mkdirs()
    file.createNewFile()

    val fos = new FileOutputStream(file)
    val fd = fos.getFD()
    fos.write(json.getBytes)
    fos.flush()
    fd.sync()
    fos.close()
  }

  /**
    * timestamp in milliseconds
    *
    * @return Long
    */
  def getTimestamp:Long = System.currentTimeMillis()

}

object JSON_PATH{
  val PERFORMERS: String = "performers"
  val CONNECTIONS: String = "connections"
  val GUID: String = "guid"
  val COMMAND: String = "command"
  val ENSEMBLES: String = "ensembles"
  val SCHEDULE: String = "schedule"
  val BACKOFF: String = "backoff"
  val SOURCE: String = "source"
  val SOURCE_NAME: String = "name"
  val SOURCE_CLASSPATH: String = "classPath"
  val SOURCE_PARAMS: String = "parameters"
  val ORCHESTRATION_NAME = "name"
  val ORCHESTRATION_TIMESTAMP = "timestamp"
  val PERFORMER_AUTO_SCALE = "autoScale"
  val PERFORMER_LOWER_BOUND = "lowerBound"
  val PERFORMER_UPPER_BOUND = "upperBound"
  val PERFORMER_BACKOFF_THRESHOLD = "backoffThreshold"
  val PERFORMER_ROUND_ROBIN = "roundRobin"
  val CONTROL_AWARE = "controlAware"
  val JAR_LOCATION = "location"
  val JAR_LOCATION_URL = "url"
  val JAR_CREDENTIALS_URL = "credentials"
  val JAR_CRED_USER = "user"
  val JAR_CRED_PASSWORD = "password"
  val PERFORMER_DISPATCHER = "dispatcher"
  val GLOBAL_PERFORMERS = "global-performers"
}

object CONFIG{

  private val log = LoggerFactory.getLogger(this.getClass)

  val FILE_APPENDER = "FEY-FILE"
  val CONSOLE_APPENDER = "FEY-CONSOLE"
  val CONTROL_AWARE_MAILBOX = "akka.fey-dispatchers.control-aware-dispatcher"

  val DEFAULT_MESSAGE = 500
  val DEFAULT_PORT = 8080

  var CHECKPOINT_DIR = ""
  var JSON_REPOSITORY = ""
  var JSON_EXTENSION = ""
  var JAR_REPOSITORY = ""
  var CHEKPOINT_ENABLED = true
  var LOG_LEVEL = ""
  var LOG_APPENDER = ""
  var MESSAGES_PER_RESIZE: Int = DEFAULT_MESSAGE
  var DYNAMIC_JAR_REPO = ""
  var DYNAMIC_JAR_FORCE_PULL = false
  var CUSTOM_DISPATCHERS: ConfigValue = null
  var MONITORING_ENABLED: Boolean = true
  var MONITORING_TYPE: String = "COMPLETE"
  var PORT = DEFAULT_PORT
  var URL_PATH = "localhost"
  var SHARED_PERFORMER_JSON_PATH: Option[String] = None

  def loadUserConfiguration(path: String) : Unit = {
    val app = {
      if(path != "" && Files.exists(Paths.get(path))) {
        ConfigFactory.parseFile(new File(path)).withFallback(ConfigFactory.load())
      }else {
          log.info("Using Fey Default Configuration")
          log.warn(s"No user configuration defined. Check if your configuration path $path is right.")
          ConfigFactory.load()
      }
    }.getConfig("fey-global-configuration").resolve()

    CHECKPOINT_DIR = app.getString("checkpoint-directory")
    JSON_REPOSITORY = app.getString("json-repository")
    JSON_EXTENSION = app.getString("json-extension")
    JAR_REPOSITORY = app.getString("jar-repository")
    CHEKPOINT_ENABLED = app.getBoolean("enable-checkpoint")
    LOG_LEVEL = app.getString("log-level").toUpperCase()
    LOG_APPENDER = app.getString("log-appender").toUpperCase()
    MESSAGES_PER_RESIZE = app.getInt("auto-scale.messages-per-resize")
    DYNAMIC_JAR_REPO = app.getString("dynamic-jar-population.downloaded-repository")
    DYNAMIC_JAR_FORCE_PULL = app.getBoolean("dynamic-jar-population.force-pull")
    CUSTOM_DISPATCHERS = app.getValue("custom-dispatchers")
    MONITORING_ENABLED = app.getBoolean("monitoring.enable")
    MONITORING_TYPE = app.getString("monitoring.type").toUpperCase()
    PORT = app.getInt("port")
    URL_PATH = app.getString("urlPath")
    SHARED_PERFORMER_JSON_PATH = if(app.getString("shared-performers").isEmpty) None else Some(app.getString("shared-performers"))

    setLogbackConfiguration()
  }

  def getDispatcherForAkka():Config = {
    val config = ConfigFactory.parseString("")
    config.withValue("fey-custom-dispatchers", CUSTOM_DISPATCHERS)
  }

  /**
    * Resets logback context configuration and loads the new one
    */
  def setLogbackConfiguration() : Unit = {
    val  context: LoggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    try {
      val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger]
      root.setLevel(getLogLevel)
      setLogAppenders(root)
    } catch {
      case e: Exception => log.error("Could not configure logback",e)
    }
    StatusPrinter.printInCaseOfErrorsOrWarnings(context)
  }

  def setLogAppenders(root: Logger) : Unit = {
    LOG_APPENDER match {
      case "FILE" =>
        root.getAppender(CONSOLE_APPENDER).stop()
      case "STDOUT" =>
        root.getAppender(FILE_APPENDER).stop()
      case "FILE_STDOUT" =>
      case x =>
        log.warn(s"Appender $x is not defined. Default to FILE_STDOUT")
    }
  }

  def getLogLevel: Level = {
    LOG_LEVEL match {
      case "DEBUG" => Level.DEBUG
      case "INFO" => Level.INFO
      case "WARN" => Level.WARN
      case "ERROR" => Level.ERROR
      case "TRACE" => Level.TRACE
      case "ALL" => Level.ALL
      case "OFF" => Level.OFF
      case x =>
        log.warn(s"Log level $x is not defined. Default to INFO")
        Level.INFO
    }
  }

  /**
    * Loads the specification for validating a Fey JSON
    */
  val JSON_SPEC: SchemaType = {
    Json.fromJson[SchemaType](Json.parse(scala.io.Source
      .fromInputStream(getClass.getResourceAsStream("/fey-json-schema-validator.json"))
      .getLines()
      .mkString(""))).get
  }
}

object GLOBAL_DEFINITIONS {
  val WATCH_SERVICE_THREAD = "FeyWatchService"
}



case class NetworkAlreadyDefined(message:String)  extends Exception(message)
case class IllegalPerformerCreation(message:String)  extends Exception(message)
case class NetworkNotDefined(message:String)  extends Exception(message)
case class CommandNotRecognized(message:String)  extends Exception(message)
case class RestartEnsemble(message:String)  extends Exception(message)
case class RestartGlobalPerformers(message: String) extends  Exception(message)
case class RestartOrchestration(message: String) extends  Exception(message)