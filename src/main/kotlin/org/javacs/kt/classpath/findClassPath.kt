package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.javacs.kt.util.KotlinLSException
import org.javacs.kt.util.firstNonNull
import org.javacs.kt.util.tryResolving
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.function.BiPredicate
import java.util.stream.Collectors

fun findClassPath(workspaceRoots: Collection<Path>): Set<Path> {
    return ensureStdLibInPaths(workspaceRoots
            .flatMap { projectFiles(it) }
            .flatMap { readProjectFile(it) }
            .union(workspaceRoots.flatMap { readLibDirectory(it) }))
}

private fun ensureStdLibInPaths(paths: Collection<Path>): Set<Path> {
    return when (paths.any { it.toString().contains("kotlin-stdlib") }) {
        true -> paths.toSet()
        false -> paths.toSet().union(backupClassPath())
    }
}

private fun backupClassPath() =
        listOfNotNull(findKotlinStdlib()).toSet()

private fun projectFiles(workspaceRoot: Path): Set<Path> {
    return Files.walk(workspaceRoot)
            .filter { isMavenBuildFile(it) || isGradleBuildFile(it) }
            .collect(Collectors.toSet())
}

private fun readProjectFile(file: Path): Set<Path> {
    if (isMavenBuildFile(file)) {
        // Project uses a Maven model
        return readPom(file)
    } else if (isGradleBuildFile(file)) {
        // Project uses a Gradle model
        return readBuildGradle(file)
    } else {
        throw IllegalArgumentException("$file is not a valid project configuration file (pom.xml or build.gradle)")
    }
}

private fun readLibDirectory(workspaceRoot: Path): Set<Path> {
    var libsDirectory = workspaceRoot.resolve("libs").toFile()
    if (libsDirectory.exists() && libsDirectory.isDirectory) {
        return libsDirectory.listFiles { _, name -> name.endsWith("jar") }.map { it.toPath() }.toSet()
    }
    return emptySet()
}

private fun isMavenBuildFile(file: Path) = file.endsWith("pom.xml")

private fun isGradleBuildFile(file: Path) = file.endsWith("build.gradle") || file.endsWith("build.gradle.kts")

private fun readPom(pom: Path): Set<Path> {
    val mavenOutput = generateMavenDependencyList(pom)
    val artifacts = mavenOutput?.let(::readMavenDependencyList)
            ?: throw KotlinLSException("No artifacts could be read from $pom")

    when {
        artifacts.isEmpty() -> LOG.warning("No artifacts found in $pom")
        artifacts.size < 5 -> LOG.info("Found ${artifacts.joinToString(", ")} in $pom")
        else -> LOG.info("Found ${artifacts.size} artifacts in $pom")
    }

    return artifacts.mapNotNull { findMavenArtifact(it, false) }.toSet()
}

private fun generateMavenDependencyList(pom: Path): Path? {
    val mavenOutput = Files.createTempFile("deps", ".txt")
    val workingDirectory = pom.toAbsolutePath().parent.toFile()
    val cmd = "${mvnCommand()} dependency:list -DincludeScope=test -DoutputFile=$mavenOutput"
    LOG.info("Run ${cmd} in $workingDirectory")
    val process = Runtime.getRuntime().exec(cmd, null, workingDirectory)

    process.inputStream.bufferedReader().use { reader ->
        while (process.isAlive()) {
            val line = reader.readLine()?.trim()
            if (line == null) break
            if ((line.length > 0) && !line.startsWith("Progress")) {
                LOG.info("Maven: $line")
            }
        }
    }

    return mavenOutput
}

private val artifact = ".*:.*:.*:.*:.*".toRegex()

private fun readMavenDependencyList(mavenOutput: Path): Set<Artifact> =
        mavenOutput.toFile()
                .readLines()
                .filter { it.matches(artifact) }
                .map { parseArtifact(it) }
                .toSet()

fun parseArtifact(rawArtifact: String, version: String? = null): Artifact {
    val parts = rawArtifact.trim().split(':')

    return when (parts.size) {
        3 -> Artifact(parts[0], parts[1], version ?: parts[2])
        5 -> Artifact(parts[0], parts[1], version ?: parts[3])
        else -> throw IllegalArgumentException("$rawArtifact is not a properly formed Maven/Gradle artifact")
    }
}

data class Artifact(val group: String, val artifact: String, val version: String) {
    override fun toString() = "$group:$artifact:$version"
}

private val userHome = Paths.get(System.getProperty("user.home"))
val mavenHome = userHome.resolve(".m2")
val gradleHome = userHome.resolve(".gradle")
// TODO: Resolve the gradleCaches dynamically instead of hardcoding this path
val gradleCaches by lazy {
    gradleHome.resolve("caches")
            .resolveStartingWith("modules")
            .resolveStartingWith("files")
}

private fun Path.resolveStartingWith(prefix: String) = Files.list(this).filter { it.fileName.toString().startsWith(prefix) }.findFirst().orElse(null)

fun findKotlinStdlib(): Path? {
    return findLocalArtifact("org.jetbrains.kotlin", "kotlin-stdlib")
}

private data class LocalArtifactDirectoryResolution(val artifactDir: Path?, val buildTool: String)

private fun findLocalArtifact(group: String, artifact: String) = firstNonNull<Path>(
        { tryResolving("$artifact using Maven") { tryFindingLocalArtifactUsing(group, artifact, findLocalArtifactDirUsingMaven(group, artifact)) } },
        { tryResolving("$artifact using Gradle") { tryFindingLocalArtifactUsing(group, artifact, findLocalArtifactDirUsingGradle(group, artifact)) } }
)

private fun tryFindingLocalArtifactUsing(group: String, artifact: String, artifactDirResolution: LocalArtifactDirectoryResolution): Path? {
    val isCorrectArtifact = BiPredicate<Path, BasicFileAttributes> { file, _ ->
        val name = file.fileName.toString()
        when (artifactDirResolution.buildTool) {
            "Maven" -> {
                val version = file.parent.fileName.toString()
                val expected = "${artifact}-${version}.jar"
                name == expected
            }
            else -> name.startsWith(artifact) && name.endsWith(".jar")
        }
    }
    return Files.list(artifactDirResolution.artifactDir)
            .sorted(::compareVersions)
            .findFirst()
            .orElse(null)
            ?.let {
                Files.find(artifactDirResolution.artifactDir, 3, isCorrectArtifact)
                        .findFirst()
                        .orElse(null)
            }
}

private fun Path.existsOrNull() =
        if (Files.exists(this)) this else null

private fun findLocalArtifactDirUsingMaven(group: String, artifact: String) =
        LocalArtifactDirectoryResolution(mavenHome.resolve("repository")
                ?.resolve(group.replace('.', File.separatorChar))
                ?.resolve(artifact)
                ?.existsOrNull(), "Maven")

private fun findLocalArtifactDirUsingGradle(group: String, artifact: String) =
        LocalArtifactDirectoryResolution(gradleCaches
                ?.resolve(group)
                ?.resolve(artifact)
                ?.existsOrNull(), "Gradle")

private fun compareVersions(left: Path, right: Path): Int {
    val leftVersion = extractVersion(left)
    val rightVersion = extractVersion(right)

    for (i in 0 until Math.min(leftVersion.size, rightVersion.size)) {
        val leftRev = leftVersion[i].reversed()
        val rightRev = rightVersion[i].reversed()
        val compare = leftRev.compareTo(rightRev)
        if (compare != 0)
            return -compare
    }

    return -leftVersion.size.compareTo(rightVersion.size)
}

private fun extractVersion(artifactVersionDir: Path): List<String> {
    return artifactVersionDir.toString().split(".")
}

private fun findMavenArtifact(a: Artifact, source: Boolean): Path? {
    val result = mavenHome.resolve("repository")
            .resolve(a.group.replace('.', File.separatorChar))
            .resolve(a.artifact)
            .resolve(a.version)
            .resolve(mavenJarName(a, source))

    if (Files.exists(result))
        return result
    else {
        LOG.warning("Couldn't find $a in $result")
        return null
    }
}

private fun mavenJarName(a: Artifact, source: Boolean) =
        if (source) "${a.artifact}-${a.version}-sources.jar"
        else "${a.artifact}-${a.version}.jar"

private var cacheMvnCommand: Path? = null

private fun mvnCommand(): Path {
    if (cacheMvnCommand == null)
        cacheMvnCommand = doMvnCommand()

    return cacheMvnCommand!!
}

private fun isOSWindows() = (File.separatorChar == '\\')

private fun doMvnCommand() = findCommandOnPath("mvn")

fun findCommandOnPath(name: String): Path? =
        if (isOSWindows()) windowsCommand(name)
        else unixCommand(name)

private fun windowsCommand(name: String) =
        findExecutableOnPath("$name.cmd")
                ?: findExecutableOnPath("$name.bat")
                ?: findExecutableOnPath("$name.exe")

private fun unixCommand(name: String) = findExecutableOnPath(name)

private fun findExecutableOnPath(fileName: String): Path? {
    for (dir in System.getenv("PATH").split(File.pathSeparator)) {
        val file = File(dir, fileName)

        if (file.isFile && file.canExecute()) {
            LOG.info("Found $fileName at ${file.absolutePath}")

            return Paths.get(file.absolutePath)
        }
    }

    return null
}
