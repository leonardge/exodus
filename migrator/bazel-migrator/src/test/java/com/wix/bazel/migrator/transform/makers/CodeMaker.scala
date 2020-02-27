package com.wix.bazel.migrator.transform.makers

import com.wix.bazel.migrator.analyze
import com.wix.bazel.migrator.analyze.{Code, Dependency}
import com.wix.bazel.migrator.model.SourceModule
import com.wix.bazel.migrator.model.makers.ModuleMaker.aModule
import com.wix.bazel.migrator.transform.makers.CodePathMaker.sourceCodePath

object CodeMaker {

  def code(filePath: String = "com/wixpress/example/SomeCode.java",
           module: SourceModule = aModule(),
           relativeSourceDirPathFromModuleRoot: String = "src/main/java",
           dependencies: List[Dependency] = Nil,
           externalDependencies: Set[String] = Set.empty): Code =
    analyze.Code(sourceCodePath(filePath, module, relativeSourceDirPathFromModuleRoot), dependencies, externalDependencies)

  def testCode(filePath: String): Code = code(filePath, relativeSourceDirPathFromModuleRoot = "src/test/java", externalDependencies = Set.empty)
}
