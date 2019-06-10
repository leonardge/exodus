package com.wixpress.build.sync

import com.wixpress.build.bazel._
import com.wixpress.build.maven._
import com.wixpress.build.sync.ArtifactoryRemoteStorage.decorateNodesWithChecksum
import com.wixpress.build.sync.BazelMavenManagedDepsSynchronizer._
import org.apache.maven.artifact.versioning.ComparableVersion
import org.slf4j.LoggerFactory

class BazelMavenManagedDepsSynchronizer(mavenDependencyResolver: MavenDependencyResolver, targetRepository: BazelRepository,
                                        dependenciesRemoteStorage: DependenciesRemoteStorage,
                                        importExternalLoadStatement: ImportExternalLoadStatement) {

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val persister = new BazelDependenciesPersister(PersistMessageHeader, targetRepository)
  private val conflictResolution = new HighestVersionConflictResolution()

  def sync(dependencyManagementSource: Coordinates, branchName: String): Unit = {
    val managedDependenciesFromMaven = mavenDependencyResolver
      .managedDependenciesOf(dependencyManagementSource)
      .forceCompileScope

    val dependenciesToUpdateWithChecksums = calcDepsNodesToSync2(managedDependenciesFromMaven, managedDependenciesFromMaven)
    persist(dependenciesToUpdateWithChecksums, branchName)
  }

  private def calcDepsNodesToSync2(dependencies: List[Dependency], managedDependencies: List[Dependency]) = {
    val localCopy = targetRepository.resetAndCheckoutMaster()
    val dependenciesToUpdate = newDependencyNodes(dependencies, managedDependencies, localCopy)
    logger.info(s"syncing ${dependenciesToUpdate.size} dependencies")

    dependenciesToUpdate.headOption.foreach { depNode => logger.info(s"First dep to sync is ${depNode.baseDependency}.") }

    if (dependenciesToUpdate.isEmpty)
      Set[BazelDependencyNode]()
    else {
      logger.info("started fetching sha256 checksums for 3rd party dependencies from artifactory...")
      val nodes = decorateNodesWithChecksum(dependenciesToUpdate)(dependenciesRemoteStorage)
      logger.info("completed fetching sha256 checksums.")
      nodes
    }
  }

  private def newDependencyNodes(dependencies: List[Dependency],
                                 managedDependencies: List[Dependency],
                                 localWorkspace: BazelLocalWorkspace) = {

    val currentDependenciesFromBazel = new BazelDependenciesReader(localWorkspace).allDependenciesAsMavenDependencies()

    logger.info(s"retrieved ${currentDependenciesFromBazel.size} dependencies from local workspace")

    val dependenciesToSync = uniqueDependenciesFrom(dependencies)
    val newManagedDependencies = dependenciesToSync

    logger.info(s"calculated ${newManagedDependencies.size} dependencies that need to added/updated")

    mavenDependencyResolver.dependencyClosureOf(newManagedDependencies, managedDependencies)
  }

  private def uniqueDependenciesFrom(possiblyConflictedDependencySet: List[Dependency]) = {
    conflictResolution.resolve(possiblyConflictedDependencySet).forceCompileScope
  }

  def persist(dependenciesToUpdate: Set[BazelDependencyNode], branchName: String): Unit = {
    if (dependenciesToUpdate.nonEmpty) {
      val localCopy = targetRepository.resetAndCheckoutMaster()

      val modifiedFiles = new BazelDependenciesWriter(localCopy, importExternalLoadStatement = importExternalLoadStatement).writeDependencies(dependenciesToUpdate)
      persister.persistWithMessage(modifiedFiles, dependenciesToUpdate.map(_.baseDependency.coordinates), Some(branchName), asPr = true)
    }
  }
}

class HighestVersionConflictResolution {
  def resolve(possiblyConflictedDependencies: Set[Dependency]): Set[Dependency] =
    possiblyConflictedDependencies.groupBy(groupIdArtifactIdClassifier)
      .mapValues(highestVersionIn)
      .values.toSet

  def resolve(possiblyConflictedDependencies: List[Dependency]): List[Dependency] =
    possiblyConflictedDependencies.groupBy(groupIdArtifactIdClassifier)
      .mapValues(highestVersionIn)
      .values.toList

  def groupIdArtifactIdClassifier(dependency: Dependency): (String, String, Option[String]) = {
    import dependency.coordinates._
    (groupId, artifactId, classifier)
  }

  private def highestVersionIn(dependencies: Iterable[Dependency]): Dependency = {
    val exclusions = dependencies.flatMap(_.exclusions).toSet
    dependencies.maxBy(d => new ComparableVersion(d.coordinates.version)).withExclusions(exclusions)
  }
}

object BazelMavenManagedDepsSynchronizer {
  val PersistMessageHeader = "Automatic update of 'third_party' import files"
}