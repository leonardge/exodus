package com.wix.bazel.migrator.analyze

import com.fasterxml.jackson.annotation.{JsonIgnoreProperties, JsonTypeInfo}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.wix.bazel.migrator.model.SourceModule
import com.wix.bazel.migrator.transform.failures.{AnalyzeException, AnalyzeFailure}

class ExceptionFormattingDependencyAnalyzer(dependencyAnalyzer: DependencyAnalyzer) extends DependencyAnalyzer {
  private val om = new ObjectMapper().registerModule(DefaultScalaModule)
    .addMixIn(classOf[AnalyzeFailure], classOf[AnalyzeFailureMixin])
    .addMixIn(classOf[Throwable], classOf[ThrowableMixin])
    .addMixIn(classOf[SourceModule], classOf[IgnoringMavenDependenciesMixin])

  override def allCodeForModule(module: SourceModule): List[Code] =
    try {
      dependencyAnalyzer.allCodeForModule(module)
    } catch {
      case e: AnalyzeException =>
        val message = om.writerWithDefaultPrettyPrinter().writeValueAsString(e.failure)
        throw new RuntimeException(message +
          """|***Detailed error is in a prettified json which starts above***
          |***Inner most AnalyzeFailure has root cause, look for it***
          |More info at https://github.com/wix-private/bazel-tooling/blob/master/migrator/docs
          |""".stripMargin)
    }
}
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "__class")
trait AnalyzeFailureMixin

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "__class")
abstract class ThrowableMixin

@JsonIgnoreProperties(Array("dependencies"))
trait IgnoringMavenDependenciesMixin