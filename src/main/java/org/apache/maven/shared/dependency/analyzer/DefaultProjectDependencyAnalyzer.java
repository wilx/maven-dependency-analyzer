/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.shared.dependency.analyzer;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.util.artifact.ArtifactIdUtils;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>DefaultProjectDependencyAnalyzer class.</p>
 *
 * @author <a href="mailto:markhobson@gmail.com">Mark Hobson</a>
 */
@Named
@Singleton
public class DefaultProjectDependencyAnalyzer implements ProjectDependencyAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultProjectDependencyAnalyzer.class);

    private static final DependencyFilter NO_ARTIFACT_RESOLUTION = (node, parents) -> false;

    /**
     * ClassAnalyzer
     */
    @Inject
    private ClassAnalyzer classAnalyzer;

    @Inject
    private List<MainDependencyClassesProvider> mainDependencyClassesProviders;

    @Inject
    private List<TestDependencyClassesProvider> testDependencyClassesProviders;

    @Inject
    private ProjectDependenciesResolver projectDependenciesResolver;

    @Inject
    private Provider<MavenSession> mavenSessionProvider;

    @Inject
    private ArtifactHandlerManager artifactHandlerManager;

    /** Constructor used by Sisu. */
    public DefaultProjectDependencyAnalyzer() {}

    DefaultProjectDependencyAnalyzer(
            ProjectDependenciesResolver projectDependenciesResolver,
            Provider<MavenSession> mavenSessionProvider,
            ArtifactHandlerManager artifactHandlerManager) {
        this.projectDependenciesResolver = projectDependenciesResolver;
        this.mavenSessionProvider = mavenSessionProvider;
        this.artifactHandlerManager = artifactHandlerManager;
    }

    /** {@inheritDoc} */
    @Override
    public ProjectDependencyAnalysis analyze(MavenProject project, Collection<String> excludedClasses)
            throws ProjectDependencyAnalyzerException {
        try {
            ClassesPatterns excludedClassesPatterns = new ClassesPatterns(excludedClasses);
            Map<Artifact, Set<String>> artifactClassMap = buildArtifactClassMap(project, excludedClassesPatterns);
            Map<String, Artifact> classToArtifactMap = buildClassToArtifactMap(artifactClassMap);

            Set<DependencyUsage> mainDependencyClasses = new HashSet<>();
            for (MainDependencyClassesProvider provider : mainDependencyClassesProviders) {
                mainDependencyClasses.addAll(provider.getDependencyClasses(project, excludedClassesPatterns));
            }

            Set<DependencyUsage> testDependencyClasses = new HashSet<>();
            for (TestDependencyClassesProvider provider : testDependencyClassesProviders) {
                testDependencyClasses.addAll(provider.getDependencyClasses(project, excludedClassesPatterns));
            }

            Set<DependencyUsage> dependencyClasses = new HashSet<>();
            dependencyClasses.addAll(mainDependencyClasses);
            dependencyClasses.addAll(testDependencyClasses);

            Set<DependencyUsage> testOnlyDependencyClasses =
                    buildTestOnlyDependencyClasses(mainDependencyClasses, testDependencyClasses);

            Map<Artifact, Set<DependencyUsage>> usedArtifacts =
                    buildUsedArtifacts(classToArtifactMap, dependencyClasses);
            Set<Artifact> mainUsedArtifacts = buildUsedArtifacts(classToArtifactMap, mainDependencyClasses)
                    .keySet();

            Set<Artifact> testArtifacts = buildUsedArtifacts(classToArtifactMap, testOnlyDependencyClasses)
                    .keySet();
            Set<Artifact> testOnlyArtifacts = removeAll(testArtifacts, mainUsedArtifacts);

            Set<Artifact> declaredArtifacts = buildDeclaredArtifacts(project, artifactHandlerManager);
            Set<Artifact> usedDeclaredArtifacts = new LinkedHashSet<>(declaredArtifacts);
            usedDeclaredArtifacts.retainAll(usedArtifacts.keySet());
            Set<Artifact> unusedDeclaredArtifacts = removeAll(declaredArtifacts, usedArtifacts.keySet());
            WrapperArtifactUsage wrapperUsage = findUsedDeclaredWrapperArtifacts(
                    project, unusedDeclaredArtifacts, usedArtifacts, mainUsedArtifacts);
            usedDeclaredArtifacts.addAll(wrapperUsage.usedArtifacts);

            // A promoted wrapper has the same main/test usage classification as the dependency it supplies.
            testOnlyArtifacts.addAll(removeAll(wrapperUsage.usedArtifacts, wrapperUsage.mainUsedArtifacts));

            Map<Artifact, Set<DependencyUsage>> usedDeclaredArtifactsWithClasses = new LinkedHashMap<>();
            for (Artifact a : usedDeclaredArtifacts) {
                usedDeclaredArtifactsWithClasses.put(a, usedArtifacts.getOrDefault(a, Collections.emptySet()));
            }

            Map<Artifact, Set<DependencyUsage>> usedUndeclaredArtifactsWithClasses = new LinkedHashMap<>(usedArtifacts);
            Set<Artifact> usedUndeclaredArtifacts =
                    removeAll(usedUndeclaredArtifactsWithClasses.keySet(), declaredArtifacts);

            usedUndeclaredArtifactsWithClasses.keySet().retainAll(usedUndeclaredArtifacts);

            unusedDeclaredArtifacts = removeAll(unusedDeclaredArtifacts, usedDeclaredArtifacts);

            Set<Artifact> testArtifactsWithNonTestScope = getTestArtifactsWithNonTestScope(project, testOnlyArtifacts);

            return new ProjectDependencyAnalysis(
                    usedDeclaredArtifactsWithClasses, usedUndeclaredArtifactsWithClasses,
                    unusedDeclaredArtifacts, testArtifactsWithNonTestScope);
        } catch (IOException exception) {
            throw new ProjectDependencyAnalyzerException("Cannot analyze dependencies", exception);
        }
    }

    /**
     * Finds declared wrappers that supply a used dependency or override a transitive version.
     *
     * @param project project to analyze
     * @param unusedDeclaredArtifacts unused declared artifacts
     * @param usedArtifacts artifacts used by the project
     * @return wrapper artifacts to treat as used
     * @implNote Only wrappers required by detected class usage are promoted. Redundant wrappers and wrappers in
     *     projects without detected usage remain unused.
     */
    Set<Artifact> getUsedDeclaredWrapperArtifacts(
            MavenProject project,
            Set<Artifact> unusedDeclaredArtifacts,
            Map<Artifact, Set<DependencyUsage>> usedArtifacts) {
        return findUsedDeclaredWrapperArtifacts(project, unusedDeclaredArtifacts, usedArtifacts, Collections.emptySet())
                .usedArtifacts;
    }

    /** Finds used wrappers and records which ones serve main-code usage. */
    private WrapperArtifactUsage findUsedDeclaredWrapperArtifacts(
            MavenProject project,
            Set<Artifact> unusedDeclaredArtifacts,
            Map<Artifact, Set<DependencyUsage>> usedArtifacts,
            Set<Artifact> mainUsedArtifacts) {
        if (unusedDeclaredArtifacts.isEmpty() || usedArtifacts.isEmpty()) {
            return new WrapperArtifactUsage();
        }

        RepositorySystemSession repositorySession = getRepositorySystemSession();
        if (repositorySession == null) {
            LOGGER.debug(
                    "Cannot identify declared dependencies with used transitive dependencies without a repository session");
            return new WrapperArtifactUsage();
        }

        // Only wrapper JARs without public API classes or service registrations are eligible for suppression.
        List<Artifact> candidates = unusedDeclaredArtifacts.stream()
                .filter(DefaultProjectDependencyAnalyzer::isWrapperJar)
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return new WrapperArtifactUsage();
        }
        Set<String> usedDependencyIds = usedArtifacts.keySet().stream()
                .map(DefaultProjectDependencyAnalyzer::toVersionlessId)
                .collect(Collectors.toSet());
        Set<String> mainUsedDependencyIds = mainUsedArtifacts.stream()
                .map(DefaultProjectDependencyAnalyzer::toVersionlessId)
                .collect(Collectors.toSet());
        Set<String> candidateIds = candidates.stream()
                .map(DefaultProjectDependencyAnalyzer::toVersionlessId)
                .collect(Collectors.toSet());
        Set<String> removableDependencyIds = unusedDeclaredArtifacts.stream()
                .map(DefaultProjectDependencyAnalyzer::toVersionlessId)
                .filter(dependencyId -> !candidateIds.contains(dependencyId))
                .collect(Collectors.toSet());

        // Exclude ordinary unused declarations that the final analysis also recommends removing.
        List<Dependency> retainedDependencies = project.getDependencies().stream()
                .filter(dependency -> !removableDependencyIds.contains(toResolverId(dependency)))
                .collect(Collectors.toList());

        Set<String> declaredDependencyIds =
                project.getDependencies().stream().map(this::toResolverId).collect(Collectors.toSet());
        // Model all used-undeclared recommendations at the versions actually analyzed.
        List<Dependency> usedUndeclaredDependencies = usedArtifacts.keySet().stream()
                .filter(artifact -> !declaredDependencyIds.contains(toVersionlessId(artifact)))
                .map(artifact -> toModelDependency(project, artifact))
                .collect(Collectors.toList());
        if (!usedUndeclaredDependencies.isEmpty()) {
            retainedDependencies = new ArrayList<>(retainedDependencies);
            retainedDependencies.addAll(usedUndeclaredDependencies);
        }

        DependencyNode projectRoot;
        try {
            projectRoot = collectDependencyGraph(
                    new DependencyGraphProject(project, retainedDependencies), repositorySession);
        } catch (DependencyResolutionException exception) {
            LOGGER.debug("Cannot identify declared wrapper dependencies", exception);
            return new WrapperArtifactUsage();
        }
        if (projectRoot == null) {
            return new WrapperArtifactUsage();
        }
        Map<String, org.eclipse.aether.artifact.Artifact> selectedArtifacts = getSelectedArtifacts(projectRoot);

        WrapperArtifactUsage result = new WrapperArtifactUsage();
        for (Artifact candidate : candidates) {
            String candidateId = toVersionlessId(candidate);
            try {
                // Pin the isolated wrapper to the versions selected by the complete project.
                List<Dependency> wrapperDependencies = retainedDependencies.stream()
                        .filter(dependency -> candidateId.equals(toResolverId(dependency)))
                        .collect(Collectors.toList());
                DependencyNode wrapperRoot = collectDependencyGraph(
                        createPinnedDependencyGraphProject(project, wrapperDependencies, selectedArtifacts),
                        repositorySession);
                Map<String, org.eclipse.aether.artifact.Artifact> wrapperArtifacts = getSelectedArtifacts(wrapperRoot);
                boolean suppliesUsedDependency = wrapperRoot != null
                        && wrapperRoot.getChildren().stream()
                                .anyMatch(node -> hasUsedTransitiveDependency(node, usedDependencyIds));
                boolean suppliesMainUsedDependency = wrapperRoot != null
                        && wrapperRoot.getChildren().stream()
                                .anyMatch(node -> hasUsedTransitiveDependency(node, mainUsedDependencyIds));
                if (suppliesUsedDependency) {
                    result.add(candidate, suppliesMainUsedDependency);
                }
                if (suppliesUsedDependency && (mainUsedDependencyIds.isEmpty() || suppliesMainUsedDependency)) {
                    continue;
                }

                // Compare the retained dependency set so mutually redundant wrappers are not all discarded.
                List<Dependency> otherDependencies = retainedDependencies.stream()
                        .filter(dependency -> !candidateId.equals(toResolverId(dependency)))
                        .collect(Collectors.toList());
                DependencyNode alternativeRoot = collectDependencyGraph(
                        new DependencyGraphProject(project, otherDependencies), repositorySession);
                if (alternativeRoot == null) {
                    continue;
                }
                Map<String, org.eclipse.aether.artifact.Artifact> alternativeSelectedArtifacts =
                        getSelectedArtifacts(alternativeRoot);
                Set<String> changedDependencyIds = getChangedSelectedDependencyIds(
                        selectedArtifacts, alternativeSelectedArtifacts, wrapperArtifacts.keySet());
                if (changedDependencyIds.isEmpty()) {
                    if (!suppliesUsedDependency) {
                        retainedDependencies = otherDependencies;
                        selectedArtifacts = alternativeSelectedArtifacts;
                    }
                    continue;
                }

                boolean protectsUsedDependency = false;
                boolean protectsMainUsedDependency = false;
                // Isolate branches to retain duplicate paths while checking every version affected by the wrapper.
                for (Dependency dependency : otherDependencies) {
                    DependencyNode branchRoot = collectDependencyGraph(
                            createPinnedDependencyGraphProject(
                                    project, Collections.singletonList(dependency), alternativeSelectedArtifacts),
                            repositorySession);
                    if (branchRoot != null) {
                        protectsUsedDependency |= branchRoot.getChildren().stream()
                                .anyMatch(
                                        node -> isUsedDependencyBranch(node, changedDependencyIds, usedDependencyIds));
                        protectsMainUsedDependency |= branchRoot.getChildren().stream()
                                .anyMatch(node ->
                                        isUsedDependencyBranch(node, changedDependencyIds, mainUsedDependencyIds));
                        if (protectsUsedDependency && (mainUsedDependencyIds.isEmpty() || protectsMainUsedDependency)) {
                            break;
                        }
                    }
                }
                if (suppliesUsedDependency || protectsUsedDependency) {
                    result.add(candidate, suppliesMainUsedDependency || protectsMainUsedDependency);
                } else {
                    retainedDependencies = otherDependencies;
                    selectedArtifacts = alternativeSelectedArtifacts;
                }
            } catch (DependencyResolutionException exception) {
                LOGGER.debug("Cannot identify whether declared dependency {} is a wrapper", candidate, exception);
            }
        }
        return result;
    }

    /**
     * Checks whether an artifact is a JAR without public classes or service registrations.
     *
     * @param artifact artifact to inspect
     * @return whether the artifact is a wrapper JAR
     */
    private static boolean isWrapperJar(Artifact artifact) {
        File file = artifact.getFile();
        if (file == null || !file.isFile() || !file.getName().endsWith(".jar")) {
            return false;
        }

        // Stop at the first public class, which is the common case for regular libraries.
        try (JarFile jarFile = new JarFile(file)) {
            Enumeration<JarEntry> jarEntries = jarFile.entries();
            while (jarEntries.hasMoreElements()) {
                JarEntry jarEntry = jarEntries.nextElement();
                String name = jarEntry.getName();
                // Service descriptors make an otherwise classless JAR behaviorally significant.
                if (!jarEntry.isDirectory() && name.startsWith("META-INF/services/")) {
                    return false;
                }
                int simpleNameStart = name.lastIndexOf('/') + 1;
                String simpleName = name.substring(simpleNameStart);
                // Descriptors do not constitute an externally accessible class API.
                if (!jarEntry.isDirectory()
                        && name.endsWith(".class")
                        && !"module-info.class".equals(simpleName)
                        && !"package-info.class".equals(simpleName)) {
                    try (InputStream input = jarFile.getInputStream(jarEntry)) {
                        if ((new ClassReader(input).getAccess() & Opcodes.ACC_PUBLIC) != 0) {
                            return false;
                        }
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            LOGGER.debug("Cannot inspect dependency classes in {}", artifact, exception);
            return false;
        }
    }

    /**
     * Checks whether a dependency subtree contains a used artifact.
     *
     * @param directDependency root of the dependency subtree
     * @param usedDependencyIds used artifact identifiers
     * @return whether the subtree contains a used artifact
     */
    private static boolean hasUsedTransitiveDependency(DependencyNode directDependency, Set<String> usedDependencyIds) {
        // The isolated graph is already mediated to the versions selected for the complete project.
        Deque<DependencyNode> remaining = new ArrayDeque<>(directDependency.getChildren());
        Set<DependencyNode> visited = Collections.newSetFromMap(new IdentityHashMap<DependencyNode, Boolean>());
        while (!remaining.isEmpty()) {
            DependencyNode node = remaining.removeFirst();
            if (visited.add(node)) {
                if (node.getArtifact() != null
                        && usedDependencyIds.contains(ArtifactIdUtils.toVersionlessId(node.getArtifact()))) {
                    return true;
                }
                remaining.addAll(node.getChildren());
            }
        }
        return false;
    }

    /** Checks whether an affected dependency descends from a used artifact. */
    private static boolean isUsedDependencyBranch(
            DependencyNode directDependency, Set<String> dependencyIds, Set<String> usedDependencyIds) {
        Deque<DependencyPath> remaining = new ArrayDeque<>();
        remaining.add(new DependencyPath(directDependency, false));
        Set<DependencyNode> visitedWithoutUsage =
                Collections.newSetFromMap(new IdentityHashMap<DependencyNode, Boolean>());
        Set<DependencyNode> visitedWithUsage =
                Collections.newSetFromMap(new IdentityHashMap<DependencyNode, Boolean>());
        while (!remaining.isEmpty()) {
            DependencyPath path = remaining.removeFirst();
            DependencyNode node = path.node;
            String dependencyId =
                    node.getArtifact() != null ? ArtifactIdUtils.toVersionlessId(node.getArtifact()) : null;
            boolean usedArtifactOnPath = path.usedArtifactOnPath || usedDependencyIds.contains(dependencyId);
            Set<DependencyNode> visited = usedArtifactOnPath ? visitedWithUsage : visitedWithoutUsage;
            if (!visited.add(node)) {
                continue;
            }
            if (usedArtifactOnPath && dependencyIds.contains(dependencyId)) {
                return true;
            }
            for (DependencyNode child : node.getChildren()) {
                remaining.addLast(new DependencyPath(child, usedArtifactOnPath));
            }
        }
        return false;
    }

    /** Returns wrapper descendants whose selected versions change without the wrapper. */
    private static Set<String> getChangedSelectedDependencyIds(
            Map<String, org.eclipse.aether.artifact.Artifact> selectedArtifacts,
            Map<String, org.eclipse.aether.artifact.Artifact> alternativeSelectedArtifacts,
            Set<String> wrapperArtifactIds) {
        Set<String> changedDependencyIds = new LinkedHashSet<>();
        for (String dependencyId : wrapperArtifactIds) {
            org.eclipse.aether.artifact.Artifact selected = selectedArtifacts.get(dependencyId);
            org.eclipse.aether.artifact.Artifact alternative = alternativeSelectedArtifacts.get(dependencyId);
            if (selected != null
                    && alternative != null
                    && !Objects.equals(selected.getBaseVersion(), alternative.getBaseVersion())) {
                changedDependencyIds.add(dependencyId);
            }
        }
        return changedDependencyIds;
    }

    /** Collects the selected artifacts from a resolved graph. */
    private static Map<String, org.eclipse.aether.artifact.Artifact> getSelectedArtifacts(DependencyNode root) {
        Map<String, org.eclipse.aether.artifact.Artifact> artifacts = new LinkedHashMap<>();
        if (root == null) {
            return artifacts;
        }
        Deque<DependencyNode> remaining = new ArrayDeque<>(root.getChildren());
        Set<DependencyNode> visited = Collections.newSetFromMap(new IdentityHashMap<DependencyNode, Boolean>());
        while (!remaining.isEmpty()) {
            DependencyNode node = remaining.removeFirst();
            if (visited.add(node) && !node.getData().containsKey(ConflictResolver.NODE_DATA_WINNER)) {
                // Discarded nodes and their subtrees do not contribute to the resolved dependency graph.
                if (node.getArtifact() != null) {
                    artifacts.put(ArtifactIdUtils.toVersionlessId(node.getArtifact()), node.getArtifact());
                }
                remaining.addAll(node.getChildren());
            }
        }
        return artifacts;
    }

    /**
     * This method defines a new way to remove the artifacts by using the conflict
     * id. We don't care about the version
     * here because there can be only 1 for a given artifact anyway.
     *
     * @param start  initial set
     * @param remove set to exclude
     * @return set with remove excluded
     */
    private static Set<Artifact> removeAll(Set<Artifact> start, Set<Artifact> remove) {
        Set<Artifact> results = new LinkedHashSet<>(start.size());

        for (Artifact artifact : start) {
            boolean found = false;

            for (Artifact artifact2 : remove) {
                if (artifact.getDependencyConflictId().equals(artifact2.getDependencyConflictId())) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                results.add(artifact);
            }
        }

        return results;
    }

    Set<Artifact> getTestArtifactsWithNonTestScope(MavenProject project, Set<Artifact> testOnlyArtifacts) {
        Set<Artifact> nonTestScopeArtifacts = new LinkedHashSet<>();

        for (Artifact artifact : testOnlyArtifacts) {
            if (Artifact.SCOPE_COMPILE.equals(artifact.getScope())) {
                nonTestScopeArtifacts.add(artifact);
            }
        }

        if (nonTestScopeArtifacts.isEmpty()) {
            return nonTestScopeArtifacts;
        }

        RepositorySystemSession repositorySession = getRepositorySystemSession();
        if (repositorySession == null) {
            LOGGER.debug("Cannot refine test-only dependency scopes without a repository session");
            return nonTestScopeArtifacts;
        }

        try {
            // Collect each non-test classpath independently and without the candidates as direct roots. Otherwise a
            // direct declaration can hide the same artifact reached transitively with a different scope.
            Set<String> nonTestDependencyIds = collectDependencyIds(
                    createDependencyGraphProject(project, nonTestScopeArtifacts, NonTestClasspath.COMPILE),
                    repositorySession);
            nonTestDependencyIds.addAll(collectDependencyIds(
                    createDependencyGraphProject(project, nonTestScopeArtifacts, NonTestClasspath.RUNTIME),
                    repositorySession));

            nonTestScopeArtifacts.removeIf(artifact -> nonTestDependencyIds.contains(toVersionlessId(artifact)));
        } catch (DependencyResolutionException exception) {
            LOGGER.debug("Cannot refine test-only dependency scopes using the non-test dependency graphs", exception);
        }

        return nonTestScopeArtifacts;
    }

    private Set<String> collectDependencyIds(MavenProject project, RepositorySystemSession repositorySession)
            throws DependencyResolutionException {
        DependencyNode root = collectDependencyGraph(project, repositorySession);

        Set<String> dependencyIds = new HashSet<>();
        if (root == null) {
            return dependencyIds;
        }
        Deque<DependencyNode> remaining = new ArrayDeque<>(root.getChildren());
        Set<DependencyNode> visited = Collections.newSetFromMap(new IdentityHashMap<DependencyNode, Boolean>());
        while (!remaining.isEmpty()) {
            DependencyNode node = remaining.removeFirst();
            if (visited.add(node)) {
                if (node.getArtifact() != null) {
                    dependencyIds.add(ArtifactIdUtils.toVersionlessId(node.getArtifact()));
                }
                remaining.addAll(node.getChildren());
            }
        }
        return dependencyIds;
    }

    /**
     * Collects a dependency graph without resolving artifact files.
     *
     * @param project project whose graph to collect
     * @param repositorySession repository session
     * @return dependency graph root, possibly {@code null}
     * @throws DependencyResolutionException if graph collection fails
     */
    private DependencyNode collectDependencyGraph(MavenProject project, RepositorySystemSession repositorySession)
            throws DependencyResolutionException {
        DependencyResolutionRequest request = new DefaultDependencyResolutionRequest(project, repositorySession);
        request.setResolutionFilter(NO_ARTIFACT_RESOLUTION);
        return projectDependenciesResolver.resolve(request).getDependencyGraph();
    }

    private MavenProject createDependencyGraphProject(
            MavenProject project, Set<Artifact> candidates, NonTestClasspath classpath) {
        Set<String> candidateIds =
                candidates.stream().map(Artifact::getDependencyConflictId).collect(Collectors.toSet());
        List<Dependency> dependencies = project.getDependencies().stream()
                .filter(dependency -> classpath.includes(dependency.getScope()))
                .filter(dependency -> !candidateIds.contains(toDependencyConflictId(dependency)))
                .collect(Collectors.toList());
        return new DependencyGraphProject(project, dependencies);
    }

    /** Creates an isolated graph project pinned to already-selected versions. */
    private MavenProject createPinnedDependencyGraphProject(
            MavenProject project,
            List<Dependency> dependencies,
            Map<String, org.eclipse.aether.artifact.Artifact> selectedArtifacts) {
        MavenProject graphProject = new DependencyGraphProject(project, dependencies);
        DependencyManagement dependencyManagement = project.getDependencyManagement() != null
                ? project.getDependencyManagement().clone()
                : new DependencyManagement();
        Set<String> managedIds = new HashSet<>();

        // Retain existing management attributes while replacing only versions selected by the complete graph.
        for (Dependency dependency : dependencyManagement.getDependencies()) {
            String dependencyId = toResolverId(dependency);
            org.eclipse.aether.artifact.Artifact selected = selectedArtifacts.get(dependencyId);
            if (selected != null) {
                dependency.setVersion(selected.getBaseVersion());
            }
            managedIds.add(dependencyId);
        }
        for (Map.Entry<String, org.eclipse.aether.artifact.Artifact> entry : selectedArtifacts.entrySet()) {
            if (managedIds.add(entry.getKey())) {
                org.eclipse.aether.artifact.Artifact selected = entry.getValue();
                Dependency dependency = new Dependency();
                dependency.setGroupId(selected.getGroupId());
                dependency.setArtifactId(selected.getArtifactId());
                dependency.setVersion(selected.getBaseVersion());
                dependency.setType(selected.getExtension());
                if (!selected.getClassifier().isEmpty()) {
                    dependency.setClassifier(selected.getClassifier());
                }
                dependencyManagement.addDependency(dependency);
            }
        }
        graphProject.getModel().setDependencyManagement(dependencyManagement);
        return graphProject;
    }

    private RepositorySystemSession getRepositorySystemSession() {
        MavenSession mavenSession = mavenSessionProvider != null ? mavenSessionProvider.get() : null;
        return mavenSession != null ? mavenSession.getRepositorySession() : null;
    }

    private String toDependencyConflictId(Dependency dependency) {
        return toDependencyConflictId(dependency, artifactHandlerManager.getArtifactHandler(dependency.getType()));
    }

    /** Returns the dependency identity used by Resolver. */
    private String toResolverId(Dependency dependency) {
        ArtifactHandler artifactHandler = artifactHandlerManager.getArtifactHandler(dependency.getType());
        String extension = artifactHandler != null ? artifactHandler.getExtension() : null;
        if (extension == null || extension.isEmpty()) {
            extension = dependency.getType();
        }
        String classifier = dependency.getClassifier();
        if (classifier == null && artifactHandler != null) {
            classifier = artifactHandler.getClassifier();
        }
        return ArtifactIdUtils.toVersionlessId(
                dependency.getGroupId(), dependency.getArtifactId(), extension, classifier);
    }

    private static String toDependencyConflictId(Dependency dependency, ArtifactHandler artifactHandler) {
        String classifier = dependency.getClassifier();
        if (classifier == null) {
            classifier = artifactHandler.getClassifier();
        }
        return ArtifactIdUtils.toVersionlessId(
                dependency.getGroupId(), dependency.getArtifactId(), dependency.getType(), classifier);
    }

    private static String toVersionlessId(Artifact artifact) {
        String extension = artifact.getArtifactHandler() != null
                ? artifact.getArtifactHandler().getExtension()
                : artifact.getType();
        return ArtifactIdUtils.toVersionlessId(
                artifact.getGroupId(), artifact.getArtifactId(), extension, artifact.getClassifier());
    }

    /** Converts a resolved artifact into an effective model dependency. */
    private Dependency toModelDependency(MavenProject project, Artifact artifact) {
        Dependency dependency = null;
        if (project.getDependencyManagement() != null) {
            dependency = project.getDependencyManagement().getDependencies().stream()
                    .filter(managedDependency -> toVersionlessId(artifact).equals(toResolverId(managedDependency)))
                    .findFirst()
                    .map(Dependency::clone)
                    .orElse(null);
        }
        if (dependency == null) {
            dependency = new Dependency();
        }
        dependency.setGroupId(artifact.getGroupId());
        dependency.setArtifactId(artifact.getArtifactId());
        dependency.setVersion(artifact.getBaseVersion());
        dependency.setScope(artifact.getScope());
        dependency.setType(artifact.getType());
        dependency.setClassifier(artifact.getClassifier());
        dependency.setOptional(artifact.isOptional());
        return dependency;
    }

    private enum NonTestClasspath {
        COMPILE {
            @Override
            boolean includes(String scope) {
                return scope == null
                        || scope.isEmpty()
                        || Artifact.SCOPE_COMPILE.equals(scope)
                        || Artifact.SCOPE_PROVIDED.equals(scope)
                        || Artifact.SCOPE_SYSTEM.equals(scope);
            }
        },
        RUNTIME {
            @Override
            boolean includes(String scope) {
                return scope == null
                        || scope.isEmpty()
                        || Artifact.SCOPE_COMPILE.equals(scope)
                        || Artifact.SCOPE_RUNTIME.equals(scope);
            }
        };

        abstract boolean includes(String scope);
    }

    /**
     * Maps dependency artifacts to their classes.
     *
     * @param project Maven project
     * @param excludedClasses patterns of classes to exclude
     * @return dependency artifacts and their classes
     * @throws IOException if a dependency cannot be read
     */
    protected Map<Artifact, Set<String>> buildArtifactClassMap(MavenProject project, ClassesPatterns excludedClasses)
            throws IOException {
        Map<Artifact, Set<String>> artifactClassMap = new LinkedHashMap<>();

        Set<Artifact> dependencyArtifacts = project.getArtifacts();

        for (Artifact artifact : dependencyArtifacts) {
            File file = artifact.getFile();

            if (file != null && file.getName().endsWith(".jar")) {
                // optimized solution for the jar case

                try (JarFile jarFile = new JarFile(file)) {
                    Enumeration<JarEntry> jarEntries = jarFile.entries();

                    Set<String> classes = new HashSet<>();

                    while (jarEntries.hasMoreElements()) {
                        String entry = jarEntries.nextElement().getName();
                        if (entry.endsWith(".class")) {
                            String className = entry.replace('/', '.');
                            className = className.substring(0, className.length() - ".class".length());
                            if (!excludedClasses.isMatch(className)) {
                                classes.add(className);
                            }
                        }
                    }

                    artifactClassMap.put(artifact, classes);
                }
            } else if (file != null && file.isDirectory()) {
                URL url = file.toURI().toURL();
                Set<String> classes = classAnalyzer.analyze(url, excludedClasses);

                artifactClassMap.put(artifact, classes);
            }
        }

        return artifactClassMap;
    }

    private static Set<DependencyUsage> buildTestOnlyDependencyClasses(
            Set<DependencyUsage> mainDependencyClasses, Set<DependencyUsage> testDependencyClasses) {
        Set<DependencyUsage> testOnlyDependencyClasses = new HashSet<>(testDependencyClasses);
        Set<String> mainDepClassNames = mainDependencyClasses.stream()
                .map(DependencyUsage::getDependencyClass)
                .collect(Collectors.toSet());
        testOnlyDependencyClasses.removeIf(u -> mainDepClassNames.contains(u.getDependencyClass()));
        return testOnlyDependencyClasses;
    }

    static Set<Artifact> buildDeclaredArtifacts(MavenProject project, ArtifactHandlerManager artifactHandlerManager) {
        Map<String, Artifact> resolvedArtifacts = project.getArtifacts().stream()
                .collect(Collectors.toMap(
                        Artifact::getDependencyConflictId,
                        Function.identity(),
                        (first, second) -> first,
                        LinkedHashMap::new));
        Set<Artifact> declaredArtifacts = new LinkedHashSet<>();
        for (Dependency dependency : project.getDependencies()) {
            ArtifactHandler artifactHandler = artifactHandlerManager.getArtifactHandler(dependency.getType());
            String dependencyConflictId = toDependencyConflictId(dependency, artifactHandler);
            Artifact artifact = resolvedArtifacts.get(dependencyConflictId);
            if (artifact == null) {
                artifact = new DefaultArtifact(
                        dependency.getGroupId(),
                        dependency.getArtifactId(),
                        VersionRange.createFromVersion(dependency.getVersion()),
                        dependency.getScope(),
                        dependency.getType(),
                        dependency.getClassifier(),
                        artifactHandler,
                        dependency.isOptional());
            }
            declaredArtifacts.add(artifact);
        }
        return declaredArtifacts;
    }

    private static final class DependencyGraphProject extends MavenProject {
        private DependencyGraphProject(MavenProject project, List<Dependency> dependencies) {
            super(project);
            setDependencies(dependencies);
        }

        @Override
        @SuppressWarnings("deprecation")
        public Set<Artifact> getDependencyArtifacts() {
            // Make ProjectDependenciesResolver collect the filtered model dependencies while retaining all other
            // decorator-visible state copied by MavenProject(MavenProject).
            return null;
        }
    }

    /** Wrapper artifacts promoted to used, split by main-code usage. */
    private static final class WrapperArtifactUsage {
        private final Set<Artifact> usedArtifacts = new LinkedHashSet<>();

        private final Set<Artifact> mainUsedArtifacts = new LinkedHashSet<>();

        private void add(Artifact artifact, boolean usedByMainCode) {
            usedArtifacts.add(artifact);
            if (usedByMainCode) {
                mainUsedArtifacts.add(artifact);
            }
        }
    }

    /** A graph node together with whether its path contains a used artifact. */
    private static final class DependencyPath {
        private final DependencyNode node;

        private final boolean usedArtifactOnPath;

        private DependencyPath(DependencyNode node, boolean usedArtifactOnPath) {
            this.node = node;
            this.usedArtifactOnPath = usedArtifactOnPath;
        }
    }

    static Map<Artifact, Set<DependencyUsage>> buildUsedArtifacts(
            Map<String, Artifact> classToArtifactMap, Set<DependencyUsage> dependencyClasses) {
        Map<Artifact, Set<DependencyUsage>> usedArtifacts = new HashMap<>();

        for (DependencyUsage classUsage : dependencyClasses) {
            Artifact artifact = classToArtifactMap.get(classUsage.getDependencyClass());

            if (artifact != null && !includedInJDK(artifact)) {
                usedArtifacts.computeIfAbsent(artifact, k -> new HashSet<>()).add(classUsage);
            }
        }

        return usedArtifacts;
    }

    // MSHARED-47 an uncommon case where a commonly used
    // third party dependency was added to the JDK
    static boolean includedInJDK(Artifact artifact) {
        if ("xml-apis".equals(artifact.getGroupId())) {
            if ("xml-apis".equals(artifact.getArtifactId())) {
                return true;
            }
        } else if ("xerces".equals(artifact.getGroupId())) {
            if ("xmlParserAPIs".equals(artifact.getArtifactId())) {
                return true;
            }
        }
        return false;
    }

    static Map<String, Artifact> buildClassToArtifactMap(Map<Artifact, Set<String>> artifactClassMap) {
        Map<String, Artifact> classToArtifactMap = new HashMap<>();

        for (Map.Entry<Artifact, Set<String>> entry : artifactClassMap.entrySet()) {
            Artifact artifact = entry.getKey();
            for (String className : entry.getValue()) {
                classToArtifactMap.putIfAbsent(className, artifact);
            }
        }

        return classToArtifactMap;
    }
}
