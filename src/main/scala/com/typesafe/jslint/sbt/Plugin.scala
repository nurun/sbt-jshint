package com.typesafe.jslint.sbt

import sbt.Keys._
import sbt._
import akka.actor.ActorRef
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Promise, Await, Future}
import scala.concurrent.duration._
import spray.json._
import com.typesafe.jslint.Jslinter
import xsbti.{Maybe, Position, Severity}
import java.lang.RuntimeException
import com.typesafe.js.sbt.JavaScriptPlugin.JavaScriptKeys
import com.typesafe.webdriver.sbt.WebDriverPlugin.WebDriverKeys
import com.typesafe.webdriver.sbt.WebDriverPlugin
import akka.util.Timeout


/**
 * The WebDriver sbt plugin plumbing around the JslintEngine
 */
object Plugin extends sbt.Plugin {

  object JslintKeys {
    val jslint = TaskKey[Unit]("jslint", "Perform JavaScript linting.")
    val jslintTest = TaskKey[Unit]("jslint-test", "Perform JavaScript linting for tests.")
    val ass = SettingKey[Option[Boolean]]("jslint-ass", "If assignment expressions should be allowed.")
    val bitwise = SettingKey[Option[Boolean]]("jslint-bitwise", "If bitwise operators should be allowed.")
    val browser = SettingKey[Option[Boolean]]("jslint-browser", "If the standard browser globals should be predefined.")
    val closure = SettingKey[Option[Boolean]]("jslint-closure", "If Google Closure idioms should be tolerated.")
    val continue = SettingKey[Option[Boolean]]("jslint-continue", "If the continuation statement should be tolerated.")
    val debug = SettingKey[Option[Boolean]]("jslint-debug", "if debugger statements should be allowed.")
    val devel = SettingKey[Option[Boolean]]("jslint-devel", "if logging should be allowed (console, alert, etc.).")
    val eqeq = SettingKey[Option[Boolean]]("jslint-eqeq", "if == should be allowed.")
    val es5 = SettingKey[Option[Boolean]]("jslint-es5", "if ES5 syntax should be allowed.")
    val evil = SettingKey[Option[Boolean]]("jslint-evil", "if eval should be allowed.")
    val forin = SettingKey[Option[Boolean]]("jslint-forin", "if for in statements need not filter.")
    val indent = SettingKey[Option[Int]]("jslint-indent", "the indentation factor.")
    val maxerr = SettingKey[Option[Int]]("jslint-maxerr", "the maximum number of errors to allow.")
    val maxlen = SettingKey[Option[Int]]("jslint-maxlen", "the maximum length of a source line.")
    val newcap = SettingKey[Option[Boolean]]("jslint-newcap", "if constructor names capitalization is ignored.")
    val node = SettingKey[Option[Boolean]]("jslint-node", "if Node.js globals should be predefined.")
    val nomen = SettingKey[Option[Boolean]]("jslint-nomen", "if names may have dangling.")
    val passfail = SettingKey[Option[Boolean]]("jslint-passfail", "if the scan should stop on first error.")
    val plusplus = SettingKey[Option[Boolean]]("jslint-plusplus", "if increment/decrement should be allowed.")
    val properties = SettingKey[Option[Boolean]]("jslint-properties", "if all property names must be declared with /*properties*/.")
    val regexp = SettingKey[Option[Boolean]]("jslint-regexp", "if the . should be allowed in regexp literals.")
    val rhino = SettingKey[Option[Boolean]]("jslint-rhino", "if the Rhino environment globals should be predefined.")
    val unparam = SettingKey[Option[Boolean]]("jslint-unparam", "if unused parameters should be tolerated.")
    val sloppy = SettingKey[Option[Boolean]]("jslint-sloppy", "if the 'use strict'; pragma is optional.")
    val stupid = SettingKey[Option[Boolean]]("jslint-stupid", "if really stupid practices are tolerated.")
    val sub = SettingKey[Option[Boolean]]("jslint-sub", "if all forms of subscript notation are tolerated.")
    val todo = SettingKey[Option[Boolean]]("jslint-todo", "if TODO comments are tolerated.")
    val vars = SettingKey[Option[Boolean]]("jslint-vars", "if multiple var statements per function should be allowed.")
    val white = SettingKey[Option[Boolean]]("jslint-white", "if sloppy whitespace is tolerated.")
    val jslintOptions = TaskKey[JsObject]("jslint-options", "An array of jslint options to pass to the linter.")
  }

  import JavaScriptKeys._
  import JslintKeys._
  import WebDriverKeys._

  def jslintSettings = Seq(
    ass := None,
    bitwise := None,
    browser := None,
    closure := None,
    continue := None,
    debug := None,
    devel := None,
    eqeq := None,
    es5 := None,
    evil := None,
    forin := None,
    indent := None,
    maxerr := None,
    maxlen := None,
    newcap := None,
    node := None,
    nomen := None,
    passfail := None,
    plusplus := None,
    properties := None,
    regexp := None,
    rhino := None,
    unparam := None,
    sloppy := None,
    stupid := None,
    sub := None,
    todo := None,
    vars := None,
    white := None,

    jslintOptions <<= state map jslintOptionsTask,

    jslint <<= (
      jslintOptions,
      webBrowser,
      unmanagedSources in JavaScript,
      parallelism,
      streams,
      reporter
      ) map (jslintTask(_, _, _, _, _, _, testing = false)),
    jslintTest <<= (
      jslintOptions,
      webBrowser,
      unmanagedSources in JavaScriptTest,
      parallelism,
      streams,
      reporter
      ) map (jslintTask(_, _, _, _, _, _, testing = true)),

    test <<= (test in Test).dependsOn(jslint, jslintTest)
  )

  private def jslintOptionsTask(state: State): JsObject = {
    val extracted = Project.extract(state)
    JsObject(List(
      extracted.get(ass in JavaScript).map(v => "ass" -> JsBoolean(v)),
      extracted.get(bitwise in JavaScript).map(v => "bitwise" -> JsBoolean(v)),
      extracted.get(browser in JavaScript).map(v => "browser" -> JsBoolean(v)),
      extracted.get(closure in JavaScript).map(v => "closure" -> JsBoolean(v)),
      extracted.get(continue in JavaScript).map(v => "continue" -> JsBoolean(v)),
      extracted.get(debug in JavaScript).map(v => "debug" -> JsBoolean(v)),
      extracted.get(devel in JavaScript).map(v => "devel" -> JsBoolean(v)),
      extracted.get(eqeq in JavaScript).map(v => "eqeq" -> JsBoolean(v)),
      extracted.get(es5 in JavaScript).map(v => "es5" -> JsBoolean(v)),
      extracted.get(evil in JavaScript).map(v => "evil" -> JsBoolean(v)),
      extracted.get(forin in JavaScript).map(v => "forin" -> JsBoolean(v)),
      extracted.get(indent in JavaScript).map(v => "indent" -> JsNumber(v)),
      extracted.get(maxerr in JavaScript).map(v => "maxerr" -> JsNumber(v)),
      extracted.get(maxlen in JavaScript).map(v => "maxlen" -> JsNumber(v)),
      extracted.get(newcap in JavaScript).map(v => "newcap" -> JsBoolean(v)),
      extracted.get(node in JavaScript).map(v => "node" -> JsBoolean(v)),
      extracted.get(nomen in JavaScript).map(v => "nomen" -> JsBoolean(v)),
      extracted.get(passfail in JavaScript).map(v => "passfail" -> JsBoolean(v)),
      extracted.get(plusplus in JavaScript).map(v => "plusplus" -> JsBoolean(v)),
      extracted.get(properties in JavaScript).map(v => "properties" -> JsBoolean(v)),
      extracted.get(regexp in JavaScript).map(v => "regexp" -> JsBoolean(v)),
      extracted.get(rhino in JavaScript).map(v => "rhino" -> JsBoolean(v)),
      extracted.get(unparam in JavaScript).map(v => "unparam" -> JsBoolean(v)),
      extracted.get(sloppy in JavaScript).map(v => "sloppy" -> JsBoolean(v)),
      extracted.get(stupid in JavaScript).map(v => "stupid" -> JsBoolean(v)),
      extracted.get(sub in JavaScript).map(v => "sub" -> JsBoolean(v)),
      extracted.get(todo in JavaScript).map(v => "todo" -> JsBoolean(v)),
      extracted.get(vars in JavaScript).map(v => "vars" -> JsBoolean(v)),
      extracted.get(white in JavaScript).map(v => "white" -> JsBoolean(v))
    ).flatten)
  }

  // TODO: This can be abstracted further so that source batches can be determined generally.
  private def jslintTask(jslintOptions: JsObject,
                         browser: ActorRef,
                         sources: Seq[File],
                         parallelism: Int,
                         s: TaskStreams,
                         reporter: LoggerReporter,
                         testing: Boolean
                          ): Unit = {

    reporter.reset()

    val testKeyword = if (testing) "test " else ""
    if (sources.size > 0) {
      s.log.info(s"JavaScript linting on ${sources.size} ${testKeyword}source(s)")
    }

    val resultBatches: Seq[Future[Seq[(File, JsArray)]]] =
      try {
        val sourceBatches = (sources grouped Math.max(sources.size / parallelism, 1)).toSeq
        sourceBatches.map(lintForSources(jslintOptions, browser, _))
      }

    val pendingResults = Future.sequence(resultBatches).flatMap(rb => Future(rb.flatten))
    val results = Await.result(pendingResults, 10.seconds)
    results.foreach {
      result =>
        logErrors(reporter, s.log, result._1, result._2)
    }
    reporter.printSummary()
    if (reporter.hasErrors()) {
      throw new LintingFailedException
    }
  }

  implicit val webDriverSystem = WebDriverPlugin.webDriverSystem
  implicit val webDriverTimeout = WebDriverPlugin.webDriverTimeout

  private val jslinter = Jslinter()

  /*
   * lints a sequence of sources and returns a future representing the results of all.
   */
  private def lintForSources(options: JsObject, browser: ActorRef, sources: Seq[File]): Future[Seq[(File, JsArray)]] = {
    jslinter.beginLint(browser).flatMap[Seq[(File, JsArray)]] {
      session =>
        val promisedResults = Seq.fill(sources.size)(Promise[(File, JsArray)]())
        lintNextSource(session, options, sources, promisedResults)
        val allResults = Future.sequence(promisedResults.map(_.future))
        allResults.onComplete {
          case _ => jslinter.endLint(session)
        }
        allResults
    }
  }

  /*
   * lint the next source in sequence and call again upon completion for the next one after that.
   */
  private def lintNextSource(session: ActorRef, options: JsObject, sources: Seq[File], promises: Seq[Promise[(File, JsArray)]]): Unit = {
    if (sources.size > 0) {
      val source = sources.head

      jslinter.lint(session, source, options)
        .map((source, _))
        .onComplete {
        c =>
          lintNextSource(session, options, sources.tail, promises.tail)
          promises.head.complete(c)
      }
    }
  }

  private def logErrors(reporter: LoggerReporter, log: Logger, source: File, jslintErrors: JsArray): Unit = {

    jslintErrors.elements.map {
      case o: JsObject =>

        def getReason(o: JsObject): String = o.fields.get("reason").get.toString()

        def logWithSeverity(o: JsObject, s: Severity): Unit = {
          val p = new Position {
            def line(): Maybe[Integer] =
              Maybe.just(java.lang.Double.parseDouble(o.fields.get("line").get.toString()).toInt)

            def lineContent(): String = o.fields.get("evidence") match {
              case Some(JsString(line)) => line
              case _ => ""
            }

            def offset(): Maybe[Integer] =
              Maybe.just(java.lang.Double.parseDouble(o.fields.get("character").get.toString()).toInt - 1)

            def pointer(): Maybe[Integer] = offset()

            def pointerSpace(): Maybe[String] = Maybe.just(
              lineContent().take(pointer().get).map {
                case '\t' => '\t'
                case x => ' '
              })

            def sourcePath(): Maybe[String] = Maybe.just(source.getPath)

            def sourceFile(): Maybe[File] = Maybe.just(source)
          }
          val r = getReason(o)
          reporter.log(p, r, s)
        }

        o.fields.get("id") match {
          case Some(JsString("(error)")) => logWithSeverity(o, Severity.Error)
          case Some(JsString("(info)")) => logWithSeverity(o, Severity.Info)
          case Some(JsString("(warn)")) => logWithSeverity(o, Severity.Warn)
          case Some(id@_) => log.error(s"Unknown type of error: $id with reason: ${getReason(o)}")
          case _ => log.error(s"Malformed error with reason: ${getReason(o)}")
        }
      case x@_ => log.error(s"Malformed result: $x")
    }
  }
}

class LintingFailedException extends RuntimeException("JavaScript linting failed") with FeedbackProvidedException {
  override def toString = getMessage
}