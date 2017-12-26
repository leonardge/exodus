package com.wix.build.maven.analysis

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}

import com.google.common.jimfs.{Configuration, Jimfs}
import com.wix.bazel.migrator.model._
import com.wix.build.maven.analysis.MavenModule._
import com.wix.build.maven.analysis.SynchronizerConversion.MavenModule2ArtifactDescriptor
import com.wixpress.build.maven.{ArtifactDescriptor, Coordinates, FakeMavenRepository, MavenScope, Dependency => SyncronizerDependency}
import org.apache.maven.model.io.xpp3.MavenXpp3Writer
import org.apache.maven.model.{Dependency, Model, Parent}
import org.specs2.execute.{AsResult, Failure, Result}
import org.specs2.matcher.{AlwaysMatcher, Matcher}
import org.specs2.mutable.{Around, SpecificationWithJUnit}
import org.specs2.specification.{Scope => SpecsScope}

import scala.collection.JavaConverters._

//noinspection TypeAnnotation
class MavenBuildSystemIT extends SpecificationWithJUnit {

  "MavenBuildSystem modules should return" >> {
    trait ctx extends SpecsScope with Around {

      def registerInMavenRepository(modules: Iterable[MavenModule]): Unit =
        modules.foreach(addWithChildrenToArtifactory)

      private def addWithChildrenToArtifactory(module: MavenModule) = {
        fakeMavenRepository.addArtifacts(module.asArtifactDescriptor)
        registerInMavenRepository(module.modules.values)
      }

      def asExternal(module:MavenModule): ExternalModule = {
        ExternalModule(module.groupId.get, module.artifactId, module.version.get)
      }


      override def around[R: AsResult](r: => R): Result = {
        repo.write()
        registerInMavenRepository(repo.rootModules)
        val result = AsResult(r)
        val changedMessageResult = addRepoContentsForFailure(result)
        fakeMavenRepository.stop()
        repo.close()
        changedMessageResult
      }

      private def addRepoContentsForFailure[R: AsResult](result: Result) =
        result match {
          case f: Failure => f.mapMessage(msg => s"\nrepo contents:\n${repo.contents}\n$msg")
          case _ => result
        }

      val fakeMavenRepository = new FakeMavenRepository(0)
      fakeMavenRepository.start()
      val buildSystem = new MavenBuildSystem(repo.root, fakeMavenRepository.url)

      def repo: Repo
    }

    "SourceModule in the repo for a single artifact repository" in new ctx {
      lazy val repo = Repo(MavenModule(groupId = "group-id",
        artifactId = "artifact-id",
        version = "some-version"))

      val sourceModules = buildSystem.modules()

      sourceModules must contain(exactly(sourceModule(relativePathFromMonoRepoRoot = "",
        externalModule = be_===(ExternalModule("group-id", "artifact-id", "some-version")))))
    }

    "SourceModules for the non pom modules in a multi module repository" in new ctx {
      lazy val repo = Repo(MavenModule(gavPrefix = "parent").withModules(
        modules = Map("child1" -> MavenModule(gavPrefix = "child1"), "child2" -> MavenModule(gavPrefix = "child2"))))

      val sourceModules = buildSystem.modules()

      sourceModules must contain(exactly(
        sourceModule(relativePathFromMonoRepoRoot = "child1", externalModule = moduleGavStartsWith("child1")),
        sourceModule(relativePathFromMonoRepoRoot = "child2", externalModule = moduleGavStartsWith("child2"))
      ))
    }

    "SourceModule for the non pom module recursively in a multi module repository" in new ctx {
      lazy val repo = Repo(
        MavenModule(gavPrefix = "grandparent").withModules(modules =
          Map("parent" -> MavenModule(gavPrefix = "parent").withModules(modules =
            Map("child" -> MavenModule(gavPrefix = "child")))
          )))

      val sourceModules = buildSystem.modules()

      sourceModules must contain(exactly(
        sourceModule(relativePathFromMonoRepoRoot = "parent/child", externalModule = moduleGavStartsWith("child"))))
    }

    "SourceModule in the repo for an artifact which gets its version from its parent" in new ctx {
      lazy val repo = Repo(MavenModule(
        groupId = Some("group"),
        artifactId = "artifact",
        version = None,
        parent = Some(MavenModule(gavPrefix = "parent"))))

      val sourceModules = buildSystem.modules()

      sourceModules must contain(exactly(sourceModule(
        externalModule = be_===(ExternalModule("group", "artifact", "parent-version")))))
    }

    "SourceModule in the repo for an artifact which gets its groupId from its parent" in new ctx {
      lazy val repo = Repo(MavenModule(
        groupId = None,
        artifactId = "artifact",
        version = Some("version"),
        parent = Some(MavenModule(gavPrefix = "parent"))))

      val sourceModules = buildSystem.modules()

      sourceModules must contain(exactly(sourceModule(
        externalModule = be_===(ExternalModule("parent-group", "artifact", "version")))))
    }

    "resource folders as scoped dependencies of the SourceModule" in new ctx {
      lazy val repo = Repo(SomeAggregatorModule.withModules(
        Map("rel" -> SomeCodeModule.withResourceFolders(
          Set("src/main/resources",
            "src/test/resources",
            "src/it/resources",
            "src/e2e/resources")))))

      val sourceModules = buildSystem.modules()

      sourceModules must contain(sourceModule(dependencies =
        be_==(ModuleDependencies(
          Map(Scope.PROD_RUNTIME ->
            Set(Target.Resources(name = "resources", belongingPackageRelativePath = "rel/src/main/resources")),
            Scope.TEST_RUNTIME ->
              Set(Target.Resources(name = "resources", belongingPackageRelativePath = "rel/src/test/resources"),
                Target.Resources(name = "resources", belongingPackageRelativePath = "rel/src/it/resources"),
                Target.Resources(name = "resources", belongingPackageRelativePath = "rel/src/e2e/resources"))
          )))
      ))
    }

    "only existing resource folders" in new ctx {
      lazy val repo = Repo(
        SomeAggregatorModule.withModules(Map("rel" -> SomeCodeModule.withResourceFolders(Set("src/it/resources")))))

      val sourceModules = buildSystem.modules()

      sourceModules must contain(sourceModule(dependencies =
        be_==(ModuleDependencies(
          Map(Scope.TEST_RUNTIME ->
            Set(Target.Resources(name = "resources", belongingPackageRelativePath = "rel/src/it/resources"))
          )))
      ))
    }

    "direct external dependencies" in new ctx {
      def someExternalModule = ExternalModule("com.google.guava", "guava", "19")
      def someExternalModuleWithClassifier = ExternalModule("com.example", "foo","15", Some("classifier"))
      def someTestExternalModule = ExternalModule("com.google.foo", "bar", "17")

      lazy val repo = Repo(SomeCodeModule
        .withJars(Scope.PROD_COMPILE -> Set(someExternalModule, someExternalModuleWithClassifier))
        .withJars(Scope.TEST_COMPILE -> Set(someTestExternalModule)))

      val sourceModules = buildSystem.modules()

      sourceModules must contain(sourceModule(dependencies =
        be_==(ModuleDependencies(
          Map(Scope.PROD_COMPILE ->
            Set(Target.MavenJar(name = "guava",
              belongingPackageRelativePath = "third_party/com/google/guava",
              originatingExternalCoordinates = someExternalModule),
              Target.MavenJar(name = "foo_classifier",
                belongingPackageRelativePath = "third_party/com/example",
                originatingExternalCoordinates = someExternalModuleWithClassifier)),
            Scope.TEST_COMPILE -> Set(Target.MavenJar(name = "bar",
              belongingPackageRelativePath = "third_party/com/google/foo",
              originatingExternalCoordinates = someTestExternalModule))))
        )))
    }

    "internal dependencies as map from scope to relative path" in new ctx {
      def someInternalModule = MavenModule(gavPrefix = "internal")
      def someTestInternalModule = MavenModule(gavPrefix = "internal_test")

      def dependingModule = MavenModule(gavPrefix = "depending")
        .withJars(Scope.PROD_COMPILE -> Set(asExternal(someInternalModule)))
        .withJars(Scope.TEST_COMPILE -> Set(asExternal(someTestInternalModule)))

      def internalLeafRelativePath = "leaf"
      def internalTestLeafRelativePath = "test-leaf"

      lazy val repo = Repo(SomeAggregatorModule.withModules(Map(
        internalLeafRelativePath -> someInternalModule,
        internalTestLeafRelativePath -> someTestInternalModule,
        "depending" -> dependingModule
      )))

      val sourceModules = buildSystem.modules()

      sourceModules must contain(sourceModule(dependencies =
        be_==(ModuleDependencies(internalDependencies =
          Map(
            Scope.PROD_COMPILE -> Set(DependencyOnSourceModule(internalLeafRelativePath)),
            Scope.TEST_COMPILE -> Set(DependencyOnSourceModule(internalTestLeafRelativePath))
          )
        ))))
    }

    "direct external dependencies with pom packaging" in new ctx {
      def someExternalModule = ExternalModule("some.package", "foo", "dontcare",packaging = Some("pom"))

      lazy val repo = Repo(SomeCodeModule
        .withJars(Scope.PROD_COMPILE -> Set(someExternalModule)))

      val sourceModules = buildSystem.modules()

      sourceModules must contain(sourceModule(dependencies =
        be_==(ModuleDependencies(
          Map(Scope.PROD_COMPILE ->
            Set(Target.MavenJar(name = "foo",
              belongingPackageRelativePath = "third_party/some/package",
              originatingExternalCoordinates = someExternalModule)))))
        ))
    }

    "if the dependency on internal modules was on prod/test code" in new ctx {
      def someCompileScopeTestClassesModule = MavenModule(gavPrefix = "internal_compile_scope_depend_on_tests")
      def someTestScopeTestClassesModule = MavenModule(gavPrefix = "internal_test_scope_depend_on_tests")

      def dependingModule = MavenModule(gavPrefix = "depending")
        .withJars(Scope.PROD_COMPILE -> Set(asExternal(someCompileScopeTestClassesModule).copy(classifier = Some("tests")), asExternal(someTestScopeTestClassesModule)))
        .withJars(Scope.TEST_COMPILE -> Set(asExternal(someTestScopeTestClassesModule).copy(classifier = Some("tests"))))

      def internalCompileScopeLeafRelativePath = "leaf"
      def internalBothScopesLeafRelativePath = "test-compile-leaf"

      lazy val repo = Repo(SomeAggregatorModule.withModules(Map(
        internalCompileScopeLeafRelativePath -> someCompileScopeTestClassesModule,
        internalBothScopesLeafRelativePath -> someTestScopeTestClassesModule,
        "depending" -> dependingModule
      )))

      val sourceModules = buildSystem.modules()

      sourceModules must contain(sourceModule(dependencies =
        be_==(ModuleDependencies(internalDependencies =
          Map(
            Scope.PROD_COMPILE -> Set(DependencyOnSourceModule(internalCompileScopeLeafRelativePath,
                                                                isDependingOnTests = true),
                                      DependencyOnSourceModule(internalBothScopesLeafRelativePath,
                                        isDependingOnTests = false)
            ),
            Scope.TEST_COMPILE -> Set(DependencyOnSourceModule(internalBothScopesLeafRelativePath,
                                                                isDependingOnTests = true
            ))
          )
        ))))
    }

    "SourceModules even if no aggregator in root of repo by reading from the first level folders" in new ctx {
      lazy val repo = Repo.withoutAggregatorAtRoot(
        "sibling1" -> MavenModule(gavPrefix = "sibling1-parent").withModules(
          modules = Map("child1" -> MavenModule(gavPrefix = "sibling1-child1"), "child2" -> MavenModule(gavPrefix = "sibling1-child2"))),
        "sibling2" -> MavenModule(gavPrefix = "sibling2")
      )

      val sourceModules = buildSystem.modules()

      sourceModules must contain(exactly(
        sourceModule(relativePathFromMonoRepoRoot = "sibling1/child1", externalModule = moduleGavStartsWith("sibling1-child1")),
        sourceModule(relativePathFromMonoRepoRoot = "sibling1/child2", externalModule = moduleGavStartsWith("sibling1-child2")),
        sourceModule(relativePathFromMonoRepoRoot = "sibling2", externalModule = moduleGavStartsWith("sibling2"))
      ))
    }

    "SourceModules ignoring siblings without poms if no aggregator in root of repo" in new ctx {
      lazy val repo = Repo.withoutAggregatorAtRoot(
        "sibling1" -> MavenModule(gavPrefix = "sibling1")
      )
      Files.createDirectory(repo.root.resolve("sibling-without-pom-which-should-be-filtered"))

      val sourceModules = buildSystem.modules()

      sourceModules must contain(exactly(
        sourceModule(relativePathFromMonoRepoRoot = "sibling1", externalModule = moduleGavStartsWith("sibling1"))
      ))
    }

  }

  def sourceModule(relativePathFromMonoRepoRoot: String,
                   externalModule: Matcher[ExternalModule]): Matcher[SourceModule] =
    sourceModule(be_===(relativePathFromMonoRepoRoot), externalModule)

  def sourceModule(relativePathFromMonoRepoRoot: Matcher[String] =
                   AlwaysMatcher[String](),
                   externalModule: Matcher[ExternalModule] =
                   AlwaysMatcher[ExternalModule](),
                   dependencies: Matcher[ModuleDependencies] =
                   AlwaysMatcher[ModuleDependencies]()): Matcher[SourceModule] =
    relativePathFromMonoRepoRoot ^^ {
      (_: SourceModule).relativePathFromMonoRepoRoot aka "relative path from mono repo root"
    } and
      externalModule ^^ {
        (_: SourceModule).externalModule aka "external module"
      } and
      dependencies ^^ {
        (_: SourceModule).dependencies aka "module dependencies"
      }

  def moduleGavStartsWith(prefix: String): Matcher[ExternalModule] =
    startWith(prefix) ^^ {
      (_: ExternalModule).groupId aka "groupId"
    } and
      startWith(prefix) ^^ {
        (_: ExternalModule).artifactId aka "artifactId"
      } and
      startWith(prefix) ^^ {
        (_: ExternalModule).version aka "version"
      }

}
object Repo {
  def apply(rootModule: MavenModule): Repo = new Repo(rootAggregatorModule = Some(rootModule))
  def withoutAggregatorAtRoot(siblingModules: (String, MavenModule)*): Repo = new Repo(siblingModules = siblingModules.toList)
}
case class Repo(rootAggregatorModule: Option[MavenModule] = None, siblingModules: List[(String, MavenModule)] = Nil) {
  private lazy val fileSystem = Jimfs.newFileSystem(Configuration.unix())
  private lazy val repoRoot = fileSystem.getPath("repoRoot")

  def root: Path = repoRoot

  def write(): Unit = rootAggregatorModule match {
    case Some(rootModule) => writeModule(repoRoot, rootModule)
    case None => siblingModules.foreach { case (relativePath, module) =>
        writeModule(repoRoot.resolve(relativePath), module)
    }
  }
  def rootModules: List[MavenModule] = rootAggregatorModule match {
    case Some(rootModule) => List(rootModule)
    case None => siblingModules.map(_._2)
  }

  def close(): Unit = fileSystem.close()

  def contents: String = {
    val accumulatedContents = new StringBuilder
    val visitor = new SimpleFileVisitor[Path] {
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        accumulateName(file)
        super.visitFile(file, attrs)
      }

      override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
        accumulateName(dir)
        super.postVisitDirectory(dir, exc)
      }

      private def accumulateName(path: Path) = {
        accumulatedContents ++= path.toString
        accumulatedContents += '\n'
      }
    }
    fileSystem.getRootDirectories.asScala.foreach(Files.walkFileTree(_, visitor))
    accumulatedContents.result()
  }

  private def writeModule(modulePath: Path, mavenModule: MavenModule): Unit = {
    writeCurrentModule(modulePath, mavenModule)
    writeChildren(modulePath, mavenModule)
  }

  private def writeResourceDirectories(modulePath: Path, mavenModule: MavenModule): Unit = {
    mavenModule.resourceFolders.map(modulePath.resolve).foreach(Files.createDirectories(_))
  }

  private def writeCurrentModule(modulePath: Path, mavenModule: MavenModule): Unit = {
    Files.createDirectories(modulePath)
    writePom(modulePath, mavenModule)
    writeResourceDirectories(modulePath, mavenModule)
  }

  private def writePom(modulePath: Path, mavenModule: MavenModule): Unit = {
    val pomPath = modulePath.resolve("pom.xml")
    val writer = Files.newBufferedWriter(pomPath)
    val pom = pomFor(mavenModule)
    new MavenXpp3Writer().write(writer, pom)
  }

  private def writeChildren(modulePath: Path, mavenModule: MavenModule): Unit = {
    mavenModule.modules.foreach { case (relative, childModule) =>
      val childModulePath = modulePath.resolve(relative)
      writeModule(childModulePath, childModule)
    }
  }

  private def pomFor(module: MavenModule) = {
    val model = new Model()
    module.groupId.foreach(model.setGroupId)
    model.setArtifactId(module.artifactId)
    module.version.foreach(model.setVersion)
    module.parent.map(moduleToParent).foreach(model.setParent)
    module.dependencies.foreach { dependenciesOfScope =>
      dependenciesOfScope._2.foreach { m =>
        val dep = new Dependency
        dep.setArtifactId(m.artifactId)
        dep.setGroupId(m.groupId)
        dep.setVersion(m.version)
        m.classifier.foreach(dep.setClassifier)
        m.packaging.filterNot(_ == "jar").foreach(dep.setType)
        dep.setScope(ScopeTranslation.toMaven(dependenciesOfScope._1))
        model.addDependency(dep)
      }
    }
    if (module.modules.nonEmpty) {
      model.setPackaging("pom")
      model.setModules(module.modules.keys.toList.asJava)
    }
    model
  }

  private def moduleToParent(mavenModule: MavenModule) = {
    val parent = new Parent
    mavenModule.groupId.foreach(parent.setGroupId)
    parent.setArtifactId(mavenModule.artifactId)
    mavenModule.version.foreach(parent.setVersion)
    parent
  }

}

case class MavenModule(groupId: Option[String] = None,
                       artifactId: String,
                       version: Option[String] = None,
                       modules: Map[String, MavenModule] = Map.empty,
                       parent: Option[MavenModule] = None,
                       resourceFolders: Set[String] = Set.empty,
                       dependencies: Set[(Scope, Set[ExternalModule])] = Set.empty) {
  def withJars(dependenciesOfScope: (Scope, Set[ExternalModule])): MavenModule =
    copy(dependencies = dependencies + dependenciesOfScope)

  //due to current limitation with how we work with Aether/FakeMavenRepository
  def resolvedGroupId: String = MavenModule.resolveGroupId(this).getOrElse(throw new IllegalStateException("no groupId defined"))

  def resolvedVersion: String = MavenModule.resolveVersion(this).getOrElse(throw new IllegalStateException("no version defined"))

  def withResourceFolders(resourceFolders: Set[String]): MavenModule = copy(resourceFolders = resourceFolders)

  def withModules(modules: Map[String, MavenModule]): MavenModule = copy(modules = modules)
}

object MavenModule {
  def apply(gavPrefix: String): MavenModule =
    MavenModule(Some(s"$gavPrefix-group"), s"$gavPrefix-artifact", Some(s"$gavPrefix-version"))

  def apply(groupId: String, artifactId: String, version: String): MavenModule =
    MavenModule(Option(groupId), artifactId, Option(version))

  val SomeCodeModule = MavenModule(gavPrefix = "some")
  val SomeAggregatorModule = MavenModule(gavPrefix = "aggregator")

  private def resolveGroupId(module: MavenModule): Option[String] = resolveProperty(module, m => m.groupId)

  private def resolveVersion(module: MavenModule): Option[String] = resolveProperty(module, m => m.version)

  @scala.annotation.tailrec
  private def resolveProperty(module: MavenModule, property: (MavenModule) => Option[String]): Option[String] = (property(module), module.parent) match {
    case (Some(version), _) => Some(version)
    case (None, None) => None
    case (None, Some(parent)) => resolveProperty(parent, property)
  }
}

object SynchronizerConversion {

  implicit class MavenModule2ArtifactDescriptor(module: MavenModule) {

    def toDependency(scopedExternalModules: (Scope, Set[ExternalModule])): Set[SyncronizerDependency] = {
      scopedExternalModules._2.map(toDependency(scopedExternalModules._1))
    }

    def toDependency(scope: Scope)(externalModule: ExternalModule): SyncronizerDependency = {
      val coordinates = Coordinates(
        groupId = externalModule.groupId,
        artifactId = externalModule.artifactId,
        version = externalModule.version,
        classifier = externalModule.classifier,
        packaging = externalModule.packaging)
      SyncronizerDependency(coordinates, MavenScope.of(ScopeTranslation.toMaven(scope)))
    }

    def asArtifactDescriptor: ArtifactDescriptor = {
      val coordinates = Coordinates(groupId = module.resolvedGroupId, artifactId = module.artifactId, version = module.resolvedVersion)
      ArtifactDescriptor.anArtifact(coordinates, deps = module.dependencies.flatMap(toDependency).toList)
    }
  }

}
