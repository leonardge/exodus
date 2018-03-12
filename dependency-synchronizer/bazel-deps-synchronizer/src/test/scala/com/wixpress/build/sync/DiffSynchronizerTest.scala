package com.wixpress.build.sync

import com.wixpress.build.{BazelExternalDependency, BazelWorkspaceDriver}
import com.wixpress.build.bazel._
import com.wixpress.build.maven.MavenMakers._
import com.wixpress.build.maven._
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.specification.Scope

class DiffSynchronizerTest extends SpecificationWithJUnit {

  "Diff Synchronizer" should {
    "not persist maven_jar because has same coordinates in managed list (still persist scala_import for references)" in new baseCtx {
      val aManagedDependency = aDependency("existing")

      givenBazelWorkspaceWithManagedDependencies(aRootDependencyNode(aManagedDependency))

      val resolver = givenFakeResolverForDependencies(rootDependencies = Set(managedDependency))
      val synchronizer = givenSynchornizerFor(resolver)

      synchronizer.sync(flattenedFullTransitiveClosureOfLocalDepsBasedOnManagedDeps = Set(aManagedDependency))

      bazelDriver.bazelExternalDependencyFor(aManagedDependency.coordinates) mustEqual BazelExternalDependency(
        mavenCoordinates = None,
        libraryRule = Some(LibraryRule.of(
          artifact = aManagedDependency.coordinates)))
    }

    "persist maven jar with local divergent version" in new baseCtx {
      givenBazelWorkspaceWithManagedDependencies(aRootDependencyNode(managedDependency))

      val divergentDependency = managedDependency.withVersion("new-version")

      val resolver = givenFakeResolverForDependencies(rootDependencies = Set(managedDependency, divergentDependency))
      val synchronizer = givenSynchornizerFor(resolver)

      synchronizer.sync(Set(divergentDependency))

      bazelDriver.bazelExternalDependencyFor(divergentDependency.coordinates) mustEqual BazelExternalDependency(
        mavenCoordinates = Some(divergentDependency.coordinates),
        libraryRule = Some(LibraryRule.of(
          artifact = divergentDependency.coordinates)))
    }

    "persist dependency with its dependencies (managed has no dependency)" in new baseCtx {
      givenBazelWorkspaceWithManagedDependencies(aRootDependencyNode(managedDependency))

      val divergentDependency = managedDependency.withVersion("new-version")

      val resolver = givenFakeResolverForDependencies(singleDependencies = Set(SingleDependency(divergentDependency, transitiveDependency)),
        rootDependencies = Set(managedDependency))
      val synchronizer = givenSynchornizerFor(resolver)

      synchronizer.sync(Set(divergentDependency, transitiveDependency))

      bazelDriver.bazelExternalDependencyFor(divergentDependency.coordinates) mustEqual BazelExternalDependency(
        mavenCoordinates = Some(divergentDependency.coordinates),
        libraryRule = Some(LibraryRule.of(
          artifact = divergentDependency.coordinates,
          runtimeDependencies = Set(transitiveDependency.coordinates))))

      bazelDriver.bazelExternalDependencyFor(transitiveDependency.coordinates) mustEqual BazelExternalDependency(
        mavenCoordinates = Some(transitiveDependency.coordinates),
        libraryRule = Some(LibraryRule.of(
          artifact = transitiveDependency.coordinates)))
    }

    "persist dependency's maven_jar without its dependency because managed has the dependency as well. persist all scala_import targets " in new baseCtx {
      givenBazelWorkspaceWithManagedDependencies(
        DependencyNode(managedDependency, Set(transitiveDependency)),
        aRootDependencyNode(transitiveDependency))

      val divergentDependency = managedDependency.withVersion("new-version")

      val resolver = givenFakeResolverForDependencies(Set(SingleDependency(divergentDependency, transitiveDependency),
        SingleDependency(managedDependency, transitiveDependency)))
      val synchronizer = givenSynchornizerFor(resolver)

      synchronizer.sync(Set(divergentDependency, transitiveDependency))

      bazelDriver.bazelExternalDependencyFor(divergentDependency.coordinates) mustEqual BazelExternalDependency(
        mavenCoordinates = Some(divergentDependency.coordinates),
        libraryRule = Some(LibraryRule.of(
          artifact = divergentDependency.coordinates,
          runtimeDependencies = Set(transitiveDependency.coordinates))))

      bazelDriver.bazelExternalDependencyFor(transitiveDependency.coordinates) mustEqual BazelExternalDependency(
        mavenCoordinates = None,
        libraryRule = Some(LibraryRule.of(
          artifact = transitiveDependency.coordinates)))
    }

    "persist maven jar with local divergent version and divergent dependency" in new baseCtx {
      val divergentTransitiveDependency = aDependency("local-transitive")

      givenBazelWorkspaceWithManagedDependencies(
        DependencyNode(managedDependency, Set(transitiveDependency)),
        aRootDependencyNode(transitiveDependency))

      val divergentDependency = managedDependency.withVersion("new-version")

      val resolver = givenFakeResolverForDependencies(Set(SingleDependency(divergentDependency, divergentTransitiveDependency),
        SingleDependency(managedDependency, transitiveDependency)))
      val synchronizer = givenSynchornizerFor(resolver)

      synchronizer.sync(Set(divergentDependency, divergentTransitiveDependency))

      bazelDriver.bazelExternalDependencyFor(divergentDependency.coordinates) mustEqual BazelExternalDependency(
        mavenCoordinates = Some(divergentDependency.coordinates),
        libraryRule = Some(LibraryRule.of(
          artifact = divergentDependency.coordinates,
          compileTimeDependencies = Set(divergentTransitiveDependency.coordinates))))

      bazelDriver.bazelExternalDependencyFor(divergentTransitiveDependency.coordinates) mustEqual BazelExternalDependency(
        mavenCoordinates = Some(divergentTransitiveDependency.coordinates),
        libraryRule = Some(LibraryRule.of(
          artifact = divergentTransitiveDependency.coordinates)))
    }

    "not persist managed dependencies not found in local deps" in new baseCtx {
      val localDependency = aDependency("local")

      givenBazelWorkspaceWithManagedDependencies(
        aRootDependencyNode(managedDependency))

      val resolver = givenFakeResolverForDependencies(rootDependencies = Set(managedDependency, localDependency))
      val synchronizer = givenSynchornizerFor(resolver)

      synchronizer.sync(Set(localDependency))

      bazelDriver.bazelExternalDependencyFor(managedDependency.coordinates) mustEqual BazelExternalDependency(
        mavenCoordinates = None,
        libraryRule = None)
    }

    "not persist maven jar even if has different scope" in new baseCtx {
      givenBazelWorkspaceWithManagedDependencies(aRootDependencyNode(managedDependency))

      val divergentDependency = managedDependency.withScope(MavenScope.Test)

      val resolver = givenFakeResolverForDependencies(rootDependencies = Set(managedDependency, divergentDependency))
      val synchronizer = givenSynchornizerFor(resolver)

      synchronizer.sync(Set(divergentDependency))

      bazelDriver.bazelExternalDependencyFor(divergentDependency.coordinates) mustEqual BazelExternalDependency(
        mavenCoordinates = None,
        libraryRule = Some(LibraryRule.of(
          artifact = divergentDependency.coordinates)))
    }

    "persist dependency's maven_jar without its exclusion. persist scala_import target with exclusion" in new baseCtx {
      givenBazelWorkspaceWithManagedDependencies(
        DependencyNode(managedDependency, Set(transitiveDependency)),
        aRootDependencyNode(transitiveDependency))

      private val transitiveCoordinates: Coordinates = transitiveDependency.coordinates
      val someExclusion = Exclusion(transitiveCoordinates.groupId, transitiveCoordinates.artifactId)

      val divergentDependency = managedDependency.withExclusions(Set(someExclusion))

      val resolver = givenFakeResolverForDependencies(singleDependencies = Set(SingleDependency(managedDependency, transitiveDependency)),
        rootDependencies = Set(divergentDependency))
      val synchronizer = givenSynchornizerFor(resolver)

      synchronizer.sync(Set(divergentDependency, transitiveDependency))

      bazelDriver.bazelExternalDependencyFor(divergentDependency.coordinates) mustEqual BazelExternalDependency(
        mavenCoordinates = Some(divergentDependency.coordinates),
        libraryRule = Some(LibraryRule.of(
          artifact = divergentDependency.coordinates,
          exclusions = Set(someExclusion))))

      bazelDriver.bazelExternalDependencyFor(transitiveDependency.coordinates) mustEqual BazelExternalDependency(
        mavenCoordinates = None,
        libraryRule = Some(LibraryRule.of(
          artifact = transitiveDependency.coordinates)))
    }
  }

  trait baseCtx extends Scope {
    private val externalFakeLocalWorkspace = new FakeLocalBazelWorkspace
    val externalFakeBazelRepository = new InMemoryBazelRepository(externalFakeLocalWorkspace)

    private val targetFakeLocalWorkspace = new FakeLocalBazelWorkspace
    val targetFakeBazelRepository = new InMemoryBazelRepository(targetFakeLocalWorkspace)

    val bazelDriver = new BazelWorkspaceDriver(targetFakeLocalWorkspace)

    val managedDependency = aDependency("base")
    val transitiveDependency = aDependency("transitive").withScope(MavenScope.Runtime)

    def givenBazelWorkspaceWithManagedDependencies(managedDeps: DependencyNode*) = {
      new BazelDependenciesWriter(externalFakeLocalWorkspace).writeDependencies(managedDeps.toSet)
    }

    def givenFakeResolverForDependencies(singleDependencies: Set[SingleDependency] = Set.empty, rootDependencies: Set[Dependency] = Set.empty) = {
      val artifactDescriptors = rootDependencies.map { dep: Dependency => ArtifactDescriptor.rootFor(dep.coordinates) }

      val dependantDescriptors = singleDependencies.map { node => ArtifactDescriptor.withSingleDependency(node.dependant.coordinates, node.dependency) }
      val dependencyDescriptors = singleDependencies.map { node => ArtifactDescriptor.rootFor(node.dependency.coordinates) }

      new FakeMavenDependencyResolver(dependantDescriptors ++ dependencyDescriptors ++ artifactDescriptors)
    }

    def givenSynchornizerFor(resolver: FakeMavenDependencyResolver) = {
      new DiffSynchronizer(externalFakeBazelRepository, targetFakeBazelRepository, resolver)
    }
  }

}

case class SingleDependency(dependant: Dependency, dependency: Dependency)