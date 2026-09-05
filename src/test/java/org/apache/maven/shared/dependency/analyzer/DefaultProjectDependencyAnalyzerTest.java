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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.collection.DependencyGraphTransformationContext;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.eclipse.aether.util.graph.transformer.JavaScopeDeriver;
import org.eclipse.aether.util.graph.transformer.JavaScopeSelector;
import org.eclipse.aether.util.graph.transformer.NearestVersionSelector;
import org.eclipse.aether.util.graph.transformer.SimpleOptionalitySelector;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests <code>DefaultProjectDependencyAnalyzer</code>.
 *
 * @see DefaultProjectDependencyAnalyzer
 */
class DefaultProjectDependencyAnalyzerTest {
    @TempDir
    private Path tempDir;

    private ProjectDependenciesResolver projectDependenciesResolver;

    private MavenSession mavenSession;

    private ArtifactHandlerManager artifactHandlerManager;

    private DefaultProjectDependencyAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        projectDependenciesResolver = mock(ProjectDependenciesResolver.class);
        mavenSession = mock(MavenSession.class);
        artifactHandlerManager = mock(ArtifactHandlerManager.class);
        when(artifactHandlerManager.getArtifactHandler(anyString())).thenReturn(mock(ArtifactHandler.class));
        analyzer = new DefaultProjectDependencyAnalyzer(
                projectDependenciesResolver, () -> mavenSession, artifactHandlerManager);
    }

    @Test
    void testBuildClassToArtifactMap() {
        Artifact artifact1 = aTestArtifact("artifact1");
        Artifact artifact2 = aTestArtifact("artifact2");

        Map<Artifact, Set<String>> artifactClassMap = new LinkedHashMap<>();
        artifactClassMap.put(artifact1, Collections.singleton("class1"));
        artifactClassMap.put(artifact2, Collections.singleton("class2"));

        Map<String, Artifact> result = DefaultProjectDependencyAnalyzer.buildClassToArtifactMap(artifactClassMap);

        assertThat(result).hasSize(2);
        assertThat(result.get("class1")).isEqualTo(artifact1);
        assertThat(result.get("class2")).isEqualTo(artifact2);
    }

    @Test
    void testBuildClassToArtifactMapWithDuplicates() {
        Artifact artifact1 = aTestArtifact("artifact1");
        Artifact artifact2 = aTestArtifact("artifact2");

        Map<Artifact, Set<String>> artifactClassMap = new LinkedHashMap<>();
        artifactClassMap.put(artifact1, Collections.singleton("duplicateClass"));
        artifactClassMap.put(artifact2, Collections.singleton("duplicateClass"));

        Map<String, Artifact> result = DefaultProjectDependencyAnalyzer.buildClassToArtifactMap(artifactClassMap);

        assertThat(result).hasSize(1);
        // Should favor the first artifact encountered
        assertThat(result.get("duplicateClass")).isEqualTo(artifact1);
    }

    @Test
    void testBuildClassToArtifactMapWithMultipleClasses() {
        Artifact artifact1 = aTestArtifact("artifact1");

        Map<Artifact, Set<String>> artifactClassMap = new LinkedHashMap<>();
        artifactClassMap.put(artifact1, new HashSet<>(Arrays.asList("class1", "class2")));

        Map<String, Artifact> result = DefaultProjectDependencyAnalyzer.buildClassToArtifactMap(artifactClassMap);

        assertThat(result).hasSize(2);
        assertThat(result.get("class1")).isEqualTo(artifact1);
        assertThat(result.get("class2")).isEqualTo(artifact1);
    }

    @Test
    void testBuildUsedArtifacts() {
        Artifact artifact1 = aTestArtifact("artifact1");
        Map<String, Artifact> classToArtifactMap = Collections.singletonMap("class1", artifact1);
        Set<DependencyUsage> dependencyClasses = Collections.singleton(new DependencyUsage("class1", "main"));

        Map<Artifact, Set<DependencyUsage>> result =
                DefaultProjectDependencyAnalyzer.buildUsedArtifacts(classToArtifactMap, dependencyClasses);

        assertThat(result).hasSize(1);
        assertThat(result.get(artifact1)).hasSize(1);
        assertThat(result.get(artifact1).iterator().next().getDependencyClass()).isEqualTo("class1");
    }

    @Test
    void testBuildUsedArtifactsWithMultipleClasses() {
        Artifact artifact1 = aTestArtifact("artifact1");
        Map<String, Artifact> classToArtifactMap = Collections.singletonMap("class1", artifact1);
        Set<DependencyUsage> dependencyClasses = new HashSet<>(
                Arrays.asList(new DependencyUsage("class1", "main"), new DependencyUsage("class1", "test")));

        Map<Artifact, Set<DependencyUsage>> result =
                DefaultProjectDependencyAnalyzer.buildUsedArtifacts(classToArtifactMap, dependencyClasses);

        assertThat(result).hasSize(1);
        assertThat(result.get(artifact1)).hasSize(2);
    }

    @Test
    void testBuildUsedArtifactsWithJDKExcluded() {
        Artifact artifact1 = aTestArtifact("xml-apis", "xml-apis");
        Map<String, Artifact> classToArtifactMap = Collections.singletonMap("class1", artifact1);
        Set<DependencyUsage> dependencyClasses = Collections.singleton(new DependencyUsage("class1", "main"));

        Map<Artifact, Set<DependencyUsage>> result =
                DefaultProjectDependencyAnalyzer.buildUsedArtifacts(classToArtifactMap, dependencyClasses);

        // Being in JDK, it should be excluded from used artifacts
        assertThat(result).isEmpty();
    }

    @Test
    void testIncludedInJDK() {
        assertThat(DefaultProjectDependencyAnalyzer.includedInJDK(aTestArtifact("xml-apis", "xml-apis")))
                .isTrue();
        assertThat(DefaultProjectDependencyAnalyzer.includedInJDK(aTestArtifact("xerces", "xmlParserAPIs")))
                .isTrue();
        assertThat(DefaultProjectDependencyAnalyzer.includedInJDK(aTestArtifact("groupId", "artifactId")))
                .isFalse();
    }

    @Test
    void testBuildDeclaredArtifactsSelectsResolvedDirectArtifacts() {
        Artifact direct = aTestArtifact("direct");
        Artifact transitive = aTestArtifact("transitive");
        MavenProject project = new MavenProject();
        project.setDependencies(Collections.singletonList(toDependency(direct)));
        project.setArtifacts(new LinkedHashSet<>(Arrays.asList(direct, transitive)));

        assertThat(DefaultProjectDependencyAnalyzer.buildDeclaredArtifacts(project, artifactHandlerManager))
                .containsExactly(direct)
                .first()
                .isSameAs(direct);
    }

    @Test
    void testBuildDeclaredArtifactsUsesDefaultClassifierFromArtifactType() {
        Artifact testJar = new DefaultArtifact(
                "groupId",
                "test-jar",
                VersionRange.createFromVersion("1.0"),
                Artifact.SCOPE_COMPILE,
                "test-jar",
                "tests",
                null);
        Dependency dependency = toDependency(testJar);
        dependency.setClassifier(null);
        MavenProject project = new MavenProject();
        project.setDependencies(Collections.singletonList(dependency));
        project.setArtifacts(Collections.singleton(testJar));
        ArtifactHandler testJarHandler = mock(ArtifactHandler.class);
        when(artifactHandlerManager.getArtifactHandler("test-jar")).thenReturn(testJarHandler);
        when(testJarHandler.getClassifier()).thenReturn("tests");

        assertThat(DefaultProjectDependencyAnalyzer.buildDeclaredArtifacts(project, artifactHandlerManager))
                .containsExactly(testJar);
    }

    @Test
    void testBuildDeclaredArtifactsRetainsRelocatedDeclaration() {
        Dependency declaration = new Dependency();
        declaration.setGroupId("axis");
        declaration.setArtifactId("axis-ant");
        declaration.setVersion("1.4");
        MavenProject project = new MavenProject();
        project.setDependencies(Collections.singletonList(declaration));
        Artifact relocated = aTestArtifact("org.apache.axis", "axis-ant");
        project.setArtifacts(Collections.singleton(relocated));

        assertThat(DefaultProjectDependencyAnalyzer.buildDeclaredArtifacts(project, artifactHandlerManager))
                .singleElement()
                .satisfies(artifact -> {
                    assertThat(artifact.getGroupId()).isEqualTo("axis");
                    assertThat(artifact.getArtifactId()).isEqualTo("axis-ant");
                    assertThat(artifact.getVersion()).isEqualTo("1.4");
                    assertThat(artifact).isNotSameAs(relocated);
                });
    }

    @Test
    void testFindsDeclaredArtifactsWithUsedTransitiveDependencies() throws Exception {
        Artifact wrapper = aJarArtifact("wrapper", 0);
        Artifact emptyWrapper = aJarArtifact("empty-wrapper");
        Artifact apiWrapper = aJarArtifact("api-wrapper", Opcodes.ACC_PUBLIC);
        Artifact unusedWrapper = aJarArtifact("unused-wrapper", 0);
        Artifact used = aTestArtifact("used");
        MavenProject project = projectWithRepositorySession(wrapper, emptyWrapper, apiWrapper, unusedWrapper);
        DependencyNode usedNode = dependencyNode("used");
        DependencyNode intermediateNode = dependencyNode("intermediate", usedNode);
        DependencyNode wrapperNode = dependencyNode("wrapper", intermediateNode);
        DependencyNode emptyWrapperNode = dependencyNode("empty-wrapper", dependencyNode("used"));
        DependencyNode unusedWrapperNode = dependencyNode("unused-wrapper", dependencyNode("unused"));
        DependencyResolutionResult projectGraph =
                dependencyGraph(wrapperNode, emptyWrapperNode, dependencyNode("api-wrapper"), unusedWrapperNode);
        DependencyResolutionResult wrapperGraph = dependencyGraph(wrapperNode);
        DependencyResolutionResult emptyWrapperGraph = dependencyGraph(emptyWrapperNode);
        DependencyResolutionResult unusedWrapperGraph = dependencyGraph(unusedWrapperNode);
        DependencyResolutionResult alternativeGraph = dependencyGraph();
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenReturn(projectGraph, wrapperGraph, emptyWrapperGraph, unusedWrapperGraph, alternativeGraph);

        Set<Artifact> result = analyzer.getUsedDeclaredWrapperArtifacts(
                project,
                new LinkedHashSet<>(Arrays.asList(wrapper, emptyWrapper, apiWrapper, unusedWrapper)),
                Collections.singletonMap(used, Collections.singleton(new DependencyUsage("Used", "Consumer"))));

        assertThat(result).containsExactly(wrapper, emptyWrapper);
        ArgumentCaptor<DependencyResolutionRequest> requestCaptor =
                ArgumentCaptor.forClass(DependencyResolutionRequest.class);
        verify(projectDependenciesResolver, times(5)).resolve(requestCaptor.capture());
        List<DependencyResolutionRequest> requests = requestCaptor.getAllValues();
        assertThat(artifactIds(requests.get(0).getMavenProject()))
                .containsExactlyInAnyOrder("wrapper", "empty-wrapper", "unused-wrapper", "used");
        assertThat(artifactIds(requests.get(1).getMavenProject())).containsExactly("wrapper");
        assertThat(artifactIds(requests.get(2).getMavenProject())).containsExactly("empty-wrapper");
        assertThat(artifactIds(requests.get(3).getMavenProject())).containsExactly("unused-wrapper");
        assertThat(artifactIds(requests.get(4).getMavenProject()))
                .containsExactlyInAnyOrder("wrapper", "empty-wrapper", "used");
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.getRepositorySession()).isSameAs(mavenSession.getRepositorySession());
            assertThat(request.getResolutionFilter())
                    .isNotNull()
                    .satisfies(filter -> assertThat(filter.accept(usedNode, Collections.emptyList()))
                            .isFalse());
        });
    }

    @Test
    void testFindsDeclaredCompatibilityVersionOverride() throws Exception {
        Artifact override = aJarArtifact("override", "2.0", 0);
        Artifact used = aTestArtifact("used");
        MavenProject project = projectWithRepositorySession(override, used);
        DependencyResolutionResult projectGraph =
                dependencyGraph(dependencyNode("override", "2.0"), dependencyNode("used"));
        DependencyResolutionResult wrapperGraph = dependencyGraph(dependencyNode("override", "2.0"));
        DependencyResolutionResult alternativeGraph =
                dependencyGraph(dependencyNode("used", dependencyNode("override", "1.0")));
        DependencyResolutionResult usedBranchGraph = alternativeGraph;
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenReturn(projectGraph, wrapperGraph, alternativeGraph, usedBranchGraph);

        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(
                        project,
                        Collections.singleton(override),
                        Collections.singletonMap(used, Collections.singleton(new DependencyUsage("Used", "Consumer")))))
                .containsExactly(override);
    }

    @Test
    void testFindsCompatibilityOverrideProvidedByWrapperDependency() throws Exception {
        Artifact wrapper = aJarArtifact("wrapper", 0);
        Artifact used = aTestArtifact("used");
        MavenProject project = projectWithRepositorySession(wrapper, used);
        DependencyResolutionResult projectGraph =
                dependencyGraph(dependencyNode("wrapper", dependencyNode("override", "2.0")), dependencyNode("used"));
        DependencyResolutionResult wrapperGraph =
                dependencyGraph(dependencyNode("wrapper", dependencyNode("override", "2.0")));
        DependencyResolutionResult alternativeGraph =
                dependencyGraph(dependencyNode("used", dependencyNode("override", "1.0")));
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenReturn(projectGraph, wrapperGraph, alternativeGraph, alternativeGraph);

        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(
                        project,
                        Collections.singleton(wrapper),
                        Collections.singletonMap(used, Collections.singleton(new DependencyUsage("Used", "Consumer")))))
                .containsExactly(wrapper);
    }

    @Test
    void testRetainsOneOfMutuallyRedundantCompatibilityWrappersInEitherOrder() throws Exception {
        Artifact wrapper = aJarArtifact("wrapper", 0);
        Artifact override = aJarArtifact("override", "2.0", 0);
        Artifact used = aTestArtifact("used");
        MavenProject wrapperFirst = projectWithRepositorySession(wrapper, used, override);
        MavenProject overrideFirst = projectWithRepositorySession(override, wrapper, used);
        DependencyResolutionResult selectedGraph =
                dependencyGraph(dependencyNode("wrapper"), dependencyNode("used"), dependencyNode("override", "2.0"));
        DependencyResolutionResult wrapperGraph =
                dependencyGraph(dependencyNode("wrapper", dependencyNode("override", "2.0")));
        DependencyResolutionResult overrideGraph = dependencyGraph(dependencyNode("override", "2.0"));
        DependencyResolutionResult withoutWrapper =
                dependencyGraph(dependencyNode("used"), dependencyNode("override", "2.0"));
        DependencyResolutionResult withoutOverride =
                dependencyGraph(dependencyNode("wrapper", dependencyNode("override", "2.0")), dependencyNode("used"));
        DependencyResolutionResult usedWithOldOverride =
                dependencyGraph(dependencyNode("used", dependencyNode("override", "1.0")));
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenReturn(
                        selectedGraph,
                        wrapperGraph,
                        withoutWrapper,
                        overrideGraph,
                        usedWithOldOverride,
                        usedWithOldOverride,
                        selectedGraph,
                        overrideGraph,
                        withoutOverride,
                        wrapperGraph,
                        usedWithOldOverride,
                        usedWithOldOverride);
        Map<Artifact, Set<DependencyUsage>> usedArtifacts =
                Collections.singletonMap(used, Collections.singleton(new DependencyUsage("Used", "Consumer")));

        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(
                        wrapperFirst, new LinkedHashSet<>(Arrays.asList(wrapper, override)), usedArtifacts))
                .containsExactly(override);
        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(
                        overrideFirst, new LinkedHashSet<>(Arrays.asList(override, wrapper)), usedArtifacts))
                .containsExactly(wrapper);
    }

    @Test
    void testIgnoresOrdinaryUnusedDependencyWhenCheckingCompatibilityOverride() throws Exception {
        Artifact unusedLibrary = aJarArtifact("unused-library", Opcodes.ACC_PUBLIC);
        Artifact override = aJarArtifact("override", "2.0", 0);
        Artifact used = aTestArtifact("used");
        MavenProject project = projectWithRepositorySession(unusedLibrary, used, override);
        DependencyResolutionResult projectGraph =
                dependencyGraph(dependencyNode("used"), dependencyNode("override", "2.0"));
        DependencyResolutionResult wrapperGraph = dependencyGraph(dependencyNode("override", "2.0"));
        DependencyResolutionResult withoutOverride =
                dependencyGraph(dependencyNode("used", dependencyNode("override", "1.0")));
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenReturn(projectGraph, wrapperGraph, withoutOverride, withoutOverride);

        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(
                        project,
                        new LinkedHashSet<>(Arrays.asList(unusedLibrary, override)),
                        Collections.singletonMap(used, Collections.singleton(new DependencyUsage("Used", "Consumer")))))
                .containsExactly(override);

        ArgumentCaptor<DependencyResolutionRequest> requestCaptor =
                ArgumentCaptor.forClass(DependencyResolutionRequest.class);
        verify(projectDependenciesResolver, times(4)).resolve(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
                .allSatisfy(request ->
                        assertThat(artifactIds(request.getMavenProject())).doesNotContain("unused-library"));
    }

    @Test
    void testPreservesUsedUndeclaredPathAfterPruningUnusedDeclaration() throws Exception {
        Artifact unusedLibrary = aJarArtifact("unused-library", Opcodes.ACC_PUBLIC);
        Artifact override = aJarArtifact("override", "2.0", 0);
        Artifact used = aTestArtifact("used");
        MavenProject project = projectWithRepositorySession(unusedLibrary, override);
        Dependency managedUsed = toDependency(used);
        Exclusion exclusion = new Exclusion();
        exclusion.setGroupId("excluded");
        exclusion.setArtifactId("artifact");
        managedUsed.addExclusion(exclusion);
        DependencyManagement dependencyManagement = new DependencyManagement();
        dependencyManagement.addDependency(managedUsed);
        project.getModel().setDependencyManagement(dependencyManagement);
        DependencyResolutionResult augmentedGraph =
                dependencyGraph(dependencyNode("override", "2.0"), dependencyNode("used"));
        DependencyResolutionResult wrapperGraph = dependencyGraph(dependencyNode("override", "2.0"));
        DependencyResolutionResult withoutOverride =
                dependencyGraph(dependencyNode("used", dependencyNode("override", "1.0")));
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenReturn(augmentedGraph, wrapperGraph, withoutOverride, withoutOverride);

        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(
                        project,
                        new LinkedHashSet<>(Arrays.asList(unusedLibrary, override)),
                        Collections.singletonMap(used, Collections.singleton(new DependencyUsage("Used", "Consumer")))))
                .containsExactly(override);

        ArgumentCaptor<DependencyResolutionRequest> requestCaptor =
                ArgumentCaptor.forClass(DependencyResolutionRequest.class);
        verify(projectDependenciesResolver, times(4)).resolve(requestCaptor.capture());
        List<DependencyResolutionRequest> requests = requestCaptor.getAllValues();
        assertThat(artifactIds(requests.get(0).getMavenProject())).containsExactlyInAnyOrder("override", "used");
        assertThat(requests.get(0).getMavenProject().getDependencies().stream()
                        .filter(dependency -> "used".equals(dependency.getArtifactId()))
                        .findFirst()
                        .orElseThrow(AssertionError::new)
                        .getExclusions())
                .singleElement()
                .satisfies(preservedExclusion -> {
                    assertThat(preservedExclusion.getGroupId()).isEqualTo("excluded");
                    assertThat(preservedExclusion.getArtifactId()).isEqualTo("artifact");
                });
        assertThat(requests)
                .allSatisfy(request ->
                        assertThat(artifactIds(request.getMavenProject())).doesNotContain("unused-library"));
    }

    @Test
    void testPinsUsedUndeclaredVersionChangedByPruning() throws Exception {
        Artifact unusedLibrary = aJarArtifact("unused-library", Opcodes.ACC_PUBLIC);
        Artifact usedLibrary = aTestArtifact("used-library");
        Artifact override = aJarArtifact("override", "2.0", 0);
        Artifact used = aTestArtifactWithVersion("used", "4.0");
        MavenProject project = projectWithRepositorySession(unusedLibrary, usedLibrary, override);
        DependencyResolutionResult selectedGraph = dependencyGraph(
                dependencyNode("used-library"), dependencyNode("override", "2.0"), dependencyNode("used", "4.0"));
        DependencyResolutionResult wrapperGraph = dependencyGraph(dependencyNode("override", "2.0"));
        DependencyResolutionResult withoutOverride = dependencyGraph(
                dependencyNode("used-library"), dependencyNode("used", "4.0", dependencyNode("override", "1.0")));
        DependencyResolutionResult usedLibraryBranch =
                dependencyGraph(dependencyNode("used-library", dependencyNode("used", "3.0")));
        DependencyResolutionResult usedBranch =
                dependencyGraph(dependencyNode("used", "4.0", dependencyNode("override", "1.0")));
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenReturn(selectedGraph, wrapperGraph, withoutOverride, usedLibraryBranch, usedBranch);
        Map<Artifact, Set<DependencyUsage>> usedArtifacts = new LinkedHashMap<>();
        usedArtifacts.put(usedLibrary, Collections.singleton(new DependencyUsage("Library", "Consumer")));
        usedArtifacts.put(used, Collections.singleton(new DependencyUsage("Used", "Consumer")));

        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(
                        project, new LinkedHashSet<>(Arrays.asList(unusedLibrary, override)), usedArtifacts))
                .containsExactly(override);

        ArgumentCaptor<DependencyResolutionRequest> requestCaptor =
                ArgumentCaptor.forClass(DependencyResolutionRequest.class);
        verify(projectDependenciesResolver, times(5)).resolve(requestCaptor.capture());
        MavenProject selectedProject = requestCaptor.getAllValues().get(0).getMavenProject();
        assertThat(artifactIds(selectedProject)).containsExactlyInAnyOrder("used-library", "override", "used");
        assertThat(dependencyVersion(selectedProject, "used")).isEqualTo("4.0");
    }

    @Test
    void testPinsUsedUndeclaredVersionBeforeRestoringAnotherDependency() throws Exception {
        Artifact unusedLibrary = aJarArtifact("unused-library", Opcodes.ACC_PUBLIC);
        Artifact usedLibrary = aTestArtifact("used-library");
        Artifact override = aJarArtifact("override", "2.0", 0);
        Artifact restored = aTestArtifact("restored");
        Artifact used = aTestArtifactWithVersion("used", "4.0");
        MavenProject project = projectWithRepositorySession(unusedLibrary, usedLibrary, override);
        DependencyResolutionResult selectedGraph = dependencyGraph(
                dependencyNode("used-library"),
                dependencyNode("override", "2.0"),
                dependencyNode("restored"),
                dependencyNode("used", "4.0"));
        DependencyResolutionResult wrapperGraph = dependencyGraph(dependencyNode("override", "2.0"));
        DependencyResolutionResult withoutOverride = dependencyGraph(
                dependencyNode("used-library"),
                dependencyNode("restored", dependencyNode("used", "3.0")),
                dependencyNode("used", "4.0", dependencyNode("override", "1.0")));
        DependencyResolutionResult usedLibraryBranch = dependencyGraph(dependencyNode("used-library"));
        DependencyResolutionResult restoredBranch =
                dependencyGraph(dependencyNode("restored", dependencyNode("used", "3.0")));
        DependencyResolutionResult usedBranch =
                dependencyGraph(dependencyNode("used", "4.0", dependencyNode("override", "1.0")));
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenReturn(
                        selectedGraph, wrapperGraph, withoutOverride, usedLibraryBranch, restoredBranch, usedBranch);
        Map<Artifact, Set<DependencyUsage>> usedArtifacts = new LinkedHashMap<>();
        usedArtifacts.put(usedLibrary, Collections.singleton(new DependencyUsage("Library", "Consumer")));
        usedArtifacts.put(restored, Collections.singleton(new DependencyUsage("Restored", "Consumer")));
        usedArtifacts.put(used, Collections.singleton(new DependencyUsage("Used", "Consumer")));

        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(
                        project, new LinkedHashSet<>(Arrays.asList(unusedLibrary, override)), usedArtifacts))
                .containsExactly(override);

        ArgumentCaptor<DependencyResolutionRequest> requestCaptor =
                ArgumentCaptor.forClass(DependencyResolutionRequest.class);
        verify(projectDependenciesResolver, times(6)).resolve(requestCaptor.capture());
        MavenProject selectedProject = requestCaptor.getAllValues().get(0).getMavenProject();
        assertThat(artifactIds(selectedProject))
                .containsExactlyInAnyOrder("used-library", "override", "restored", "used");
        assertThat(dependencyVersion(selectedProject, "used")).isEqualTo("4.0");
    }

    @Test
    void testDoesNotUseSiblingUsageToJustifyCompatibilityOverride() throws Exception {
        Artifact library = aTestArtifact("library");
        Artifact override = aJarArtifact("override", "2.0", 0);
        Artifact used = aTestArtifact("used");
        MavenProject project = projectWithRepositorySession(library, used, override);
        DependencyResolutionResult projectGraph = dependencyGraph(
                dependencyNode("library", dependencyNode("used")),
                dependencyNode("used"),
                dependencyNode("override", "2.0"));
        DependencyResolutionResult wrapperGraph = dependencyGraph(dependencyNode("override", "2.0"));
        DependencyResolutionResult alternativeGraph = dependencyGraph(
                dependencyNode("library", dependencyNode("used"), dependencyNode("override", "1.0")),
                dependencyNode("used"));
        DependencyResolutionResult libraryBranch =
                dependencyGraph(dependencyNode("library", dependencyNode("used"), dependencyNode("override", "1.0")));
        DependencyResolutionResult usedBranch = dependencyGraph(dependencyNode("used"));
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenReturn(projectGraph, wrapperGraph, alternativeGraph, libraryBranch, usedBranch);

        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(
                        project,
                        Collections.singleton(override),
                        Collections.singletonMap(used, Collections.singleton(new DependencyUsage("Used", "Consumer")))))
                .isEmpty();
        verify(projectDependenciesResolver, times(5)).resolve(any(DependencyResolutionRequest.class));
    }

    @Test
    void testUsesSelectedIntermediateVersionFromVerboseGraphForWrapperDependencies() throws Exception {
        Artifact wrapper = aJarArtifact("wrapper", 0);
        Artifact used = aTestArtifact("api");
        MavenProject project = projectWithRepositorySession(wrapper, aTestArtifact("middle"), used);
        ((DefaultRepositorySystemSession) mavenSession.getRepositorySession())
                .setConfigProperty(ConflictResolver.CONFIG_PROP_VERBOSE, true);
        DependencyNode discardedMiddle = dependencyNode("middle", "1.0", dependencyNode("api"));
        DependencyResolutionResult projectGraph = verboseConflictGraph(
                dependencyNode("wrapper", discardedMiddle), dependencyNode("middle", "2.0"), dependencyNode("api"));
        assertThat(hasConflictLoser(projectGraph.getDependencyGraph())).isTrue();
        DependencyResolutionResult wrapperGraph =
                dependencyGraph(dependencyNode("wrapper", dependencyNode("middle", "2.0")));
        DependencyResolutionResult alternativeGraph =
                dependencyGraph(dependencyNode("middle", "2.0"), dependencyNode("api"));
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenReturn(projectGraph, wrapperGraph, alternativeGraph);

        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(
                        project,
                        Collections.singleton(wrapper),
                        Collections.singletonMap(used, Collections.singleton(new DependencyUsage("Api", "Consumer")))))
                .isEmpty();

        ArgumentCaptor<DependencyResolutionRequest> requestCaptor =
                ArgumentCaptor.forClass(DependencyResolutionRequest.class);
        verify(projectDependenciesResolver, times(3)).resolve(requestCaptor.capture());
        assertThat(managedVersion(requestCaptor.getAllValues().get(1).getMavenProject(), "middle"))
                .isEqualTo("2.0");
    }

    @Test
    void testPreservesExclusionsWhenUsingSelectedDependencyVersion() throws Exception {
        Artifact wrapper = aJarArtifact("wrapper", 0);
        Artifact middle = aTestArtifact("middle");
        Artifact used = aTestArtifact("api");
        MavenProject project = projectWithRepositorySession(wrapper, middle, used);
        Dependency managedMiddle = toDependency(middle);
        managedMiddle.setVersion("1.0");
        Exclusion exclusion = new Exclusion();
        exclusion.setGroupId("groupId");
        exclusion.setArtifactId("api");
        managedMiddle.addExclusion(exclusion);
        DependencyManagement dependencyManagement = new DependencyManagement();
        dependencyManagement.addDependency(managedMiddle);
        project.getModel().setDependencyManagement(dependencyManagement);
        DependencyResolutionResult projectGraph = dependencyGraph(
                dependencyNode("wrapper"),
                dependencyNode("middle", "2.0", dependencyNode("api")),
                dependencyNode("api"));
        // Resolver applies the managed exclusion while collecting the version-pinned isolated branch.
        DependencyResolutionResult excludedWrapperGraph =
                dependencyGraph(dependencyNode("wrapper", dependencyNode("middle", "2.0")));
        DependencyResolutionResult alternativeGraph =
                dependencyGraph(dependencyNode("middle", "2.0", dependencyNode("api")), dependencyNode("api"));
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenReturn(projectGraph, excludedWrapperGraph, alternativeGraph);

        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(
                        project,
                        Collections.singleton(wrapper),
                        Collections.singletonMap(used, Collections.singleton(new DependencyUsage("Api", "Consumer")))))
                .isEmpty();

        ArgumentCaptor<DependencyResolutionRequest> requestCaptor =
                ArgumentCaptor.forClass(DependencyResolutionRequest.class);
        verify(projectDependenciesResolver, times(3)).resolve(requestCaptor.capture());
        Dependency pinnedMiddle = requestCaptor
                .getAllValues()
                .get(1)
                .getMavenProject()
                .getDependencyManagement()
                .getDependencies()
                .get(0);
        assertThat(pinnedMiddle.getVersion()).isEqualTo("2.0");
        assertThat(pinnedMiddle.getExclusions()).singleElement().satisfies(pinnedExclusion -> {
            assertThat(pinnedExclusion.getGroupId()).isEqualTo("groupId");
            assertThat(pinnedExclusion.getArtifactId()).isEqualTo("api");
        });
    }

    @Test
    void testFindsCompatibilityOverrideThroughDuplicatePathsInEitherOrder() throws Exception {
        Artifact override = aJarArtifact("override", "2.0", 0);
        Artifact used = aTestArtifact("used");
        Artifact unused = aTestArtifact("unused");
        MavenProject unusedFirst = projectWithRepositorySession(override, unused, used);
        MavenProject usedFirst = projectWithRepositorySession(override, used, unused);
        DependencyResolutionResult unusedFirstProjectGraph =
                dependencyGraph(dependencyNode("override", "2.0"), dependencyNode("unused"), dependencyNode("used"));
        DependencyResolutionResult unusedFirstWrapperGraph = dependencyGraph(dependencyNode("override", "2.0"));
        DependencyResolutionResult unusedFirstAlternativeGraph =
                dependencyGraph(dependencyNode("unused", dependencyNode("override", "1.0")), dependencyNode("used"));
        DependencyResolutionResult unusedBranchGraph =
                dependencyGraph(dependencyNode("unused", dependencyNode("override", "1.0")));
        DependencyResolutionResult usedBranchGraph =
                dependencyGraph(dependencyNode("used", dependencyNode("override", "1.0")));
        DependencyResolutionResult usedFirstProjectGraph =
                dependencyGraph(dependencyNode("override", "2.0"), dependencyNode("used"), dependencyNode("unused"));
        DependencyResolutionResult usedFirstWrapperGraph = dependencyGraph(dependencyNode("override", "2.0"));
        DependencyResolutionResult usedFirstAlternativeGraph =
                dependencyGraph(dependencyNode("used", dependencyNode("override", "1.0")), dependencyNode("unused"));
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenReturn(
                        unusedFirstProjectGraph,
                        unusedFirstWrapperGraph,
                        unusedFirstAlternativeGraph,
                        unusedBranchGraph,
                        usedBranchGraph,
                        usedFirstProjectGraph,
                        usedFirstWrapperGraph,
                        usedFirstAlternativeGraph,
                        usedBranchGraph);
        Map<Artifact, Set<DependencyUsage>> usedArtifacts =
                Collections.singletonMap(used, Collections.singleton(new DependencyUsage("Used", "Consumer")));

        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(
                        unusedFirst, Collections.singleton(override), usedArtifacts))
                .containsExactly(override);
        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(usedFirst, Collections.singleton(override), usedArtifacts))
                .containsExactly(override);
    }

    @Test
    void testIgnoresOverrideAlreadySelectedTransitively() throws Exception {
        Artifact override = aJarArtifact("override", "2.0", 0);
        Artifact usedA = aTestArtifact("used-a");
        Artifact usedB = aTestArtifact("used-b");
        MavenProject project = projectWithRepositorySession(override, usedA, usedB);
        DependencyResolutionResult projectGraph =
                dependencyGraph(dependencyNode("override", "2.0"), dependencyNode("used-a"), dependencyNode("used-b"));
        DependencyResolutionResult wrapperGraph = dependencyGraph(dependencyNode("override", "2.0"));
        DependencyResolutionResult alternativeGraph =
                dependencyGraph(dependencyNode("used-a", dependencyNode("override", "2.0")), dependencyNode("used-b"));
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenReturn(projectGraph, wrapperGraph, alternativeGraph);
        Map<Artifact, Set<DependencyUsage>> usedArtifacts = new LinkedHashMap<>();
        usedArtifacts.put(usedA, Collections.singleton(new DependencyUsage("UsedA", "Consumer")));
        usedArtifacts.put(usedB, Collections.singleton(new DependencyUsage("UsedB", "Consumer")));

        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(project, Collections.singleton(override), usedArtifacts))
                .isEmpty();
        verify(projectDependenciesResolver, times(3)).resolve(any(DependencyResolutionRequest.class));
    }

    @Test
    void testFindsTestJarWrapperWithUsedDependency() throws Exception {
        ArtifactHandler handler = mock(ArtifactHandler.class);
        when(handler.getExtension()).thenReturn("jar");
        when(handler.getClassifier()).thenReturn("tests");
        Artifact jar = aJarArtifact("test-wrapper", 0);
        Artifact testJar = new DefaultArtifact(
                "groupId",
                "test-wrapper",
                VersionRange.createFromVersion("1.0"),
                Artifact.SCOPE_COMPILE,
                "test-jar",
                "tests",
                handler);
        testJar.setFile(jar.getFile());
        Artifact used = aTestArtifact("used");
        MavenProject project = projectWithRepositorySession(testJar);
        DependencyNode testJarNode = new DefaultDependencyNode(
                new org.eclipse.aether.artifact.DefaultArtifact("groupId", "test-wrapper", "tests", "jar", "1.0"));
        testJarNode.setChildren(Collections.singletonList(dependencyNode("used")));
        DependencyResolutionResult graph = dependencyGraph(testJarNode);
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenReturn(graph, graph);

        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(
                        project,
                        Collections.singleton(testJar),
                        Collections.singletonMap(used, Collections.singleton(new DependencyUsage("Used", "Consumer")))))
                .containsExactly(testJar);
    }

    @Test
    void testIgnoresPublicClassAndServiceRegistrationJars() throws Exception {
        Artifact publicProvider = aJarArtifact("public-provider", Opcodes.ACC_PUBLIC);
        Artifact serviceRegistration = aServiceRegistrationJarArtifact("service-registration");
        Artifact used = aTestArtifact("used");
        MavenProject project = projectWithRepositorySession(publicProvider, serviceRegistration);

        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(
                        project,
                        new LinkedHashSet<>(Arrays.asList(publicProvider, serviceRegistration)),
                        Collections.singletonMap(used, Collections.singleton(new DependencyUsage("Used", "Consumer")))))
                .isEmpty();
        verifyNoInteractions(projectDependenciesResolver);
    }

    @Test
    void testIgnoresUsedTransitiveDependenciesWhenGraphCollectionFails() throws Exception {
        Artifact wrapper = aJarArtifact("wrapper", 0);
        Artifact used = aTestArtifact("used");
        MavenProject project = projectWithRepositorySession(wrapper);
        DependencyResolutionResult failedResult = mock(DependencyResolutionResult.class);
        when(failedResult.getUnresolvedDependencies()).thenReturn(Collections.emptyList());
        when(failedResult.getCollectionErrors()).thenReturn(Collections.emptyList());
        DependencyResolutionException failure =
                new DependencyResolutionException(failedResult, "collection failed", new Exception("failure"));
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenThrow(failure);

        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(
                        project,
                        Collections.singleton(wrapper),
                        Collections.singletonMap(used, Collections.singleton(new DependencyUsage("Used", "Consumer")))))
                .isEmpty();
    }

    @Test
    void testIgnoresUsedTransitiveDependenciesWithoutRepositorySession() {
        Artifact wrapper = aTestArtifact("wrapper");
        Artifact used = aTestArtifact("used");

        assertThat(analyzer.getUsedDeclaredWrapperArtifacts(
                        new MavenProject(),
                        Collections.singleton(wrapper),
                        Collections.singletonMap(used, Collections.singleton(new DependencyUsage("Used", "Consumer")))))
                .isEmpty();
        verifyNoInteractions(projectDependenciesResolver);
    }

    @Test
    void testRetainsTestOnlyCompileDependencyWithoutRepositorySession() {
        Artifact candidate = aTestArtifact("candidate");

        assertThat(analyzer.getTestArtifactsWithNonTestScope(new MavenProject(), Collections.singleton(candidate)))
                .containsExactly(candidate);
        verifyNoInteractions(projectDependenciesResolver);
    }

    @Test
    void testRetainsTestOnlyCompileDependencyWhenRuntimeGraphCollectionFails() throws Exception {
        Artifact candidate = aTestArtifact("candidate");
        MavenProject project = projectWithRepositorySession(candidate);
        DependencyResolutionResult failedResult = mock(DependencyResolutionResult.class);
        when(failedResult.getUnresolvedDependencies()).thenReturn(Collections.emptyList());
        when(failedResult.getCollectionErrors()).thenReturn(Collections.emptyList());
        DependencyResolutionException failure =
                new DependencyResolutionException(failedResult, "collection failed", new Exception("failure"));
        DependencyResolutionResult compileResult = dependencyGraph("candidate", "2.0");
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenReturn(compileResult)
                .thenThrow(failure);

        assertThat(analyzer.getTestArtifactsWithNonTestScope(project, Collections.singleton(candidate)))
                .containsExactly(candidate);
    }

    @Test
    void testRemovesDependenciesReachableFromCompileOrRuntimeGraph() throws Exception {
        Artifact compileCandidate = aTestArtifact("compile-candidate");
        Artifact runtimeCandidate = aTestArtifact("runtime-candidate");
        Artifact compile = aTestArtifactWithScope("compile", Artifact.SCOPE_COMPILE);
        Artifact provided = aTestArtifactWithScope("provided", Artifact.SCOPE_PROVIDED);
        Artifact system = aTestArtifactWithScope("system", Artifact.SCOPE_SYSTEM);
        Artifact runtime = aTestArtifactWithScope("runtime", Artifact.SCOPE_RUNTIME);
        Artifact test = aTestArtifactWithScope("test", Artifact.SCOPE_TEST);
        MavenProject project = projectWithRepositorySession(
                compileCandidate, runtimeCandidate, compile, provided, system, runtime, test);
        Model originalModel = new Model();
        Profile activeProfile = new Profile();
        activeProfile.setId("active");
        Artifact resolvedState = aTestArtifact("resolved-state");
        Map<String, Artifact> managedVersionMap = Collections.singletonMap("managed", aTestArtifact("managed"));
        project.setOriginalModel(originalModel);
        project.setActiveProfiles(Collections.singletonList(activeProfile));
        project.setArtifacts(Collections.singleton(resolvedState));
        project.setManagedVersionMap(managedVersionMap);
        project.setExecutionRoot(true);

        DependencyResolutionResult compileResult = dependencyGraph("compile-candidate", "2.0");
        DependencyResolutionResult runtimeResult = dependencyGraph("runtime-candidate", "2.0");
        when(projectDependenciesResolver.resolve(any(DependencyResolutionRequest.class)))
                .thenReturn(compileResult, runtimeResult);

        Set<Artifact> candidates = new LinkedHashSet<>(Arrays.asList(compileCandidate, runtimeCandidate));
        assertThat(analyzer.getTestArtifactsWithNonTestScope(project, candidates))
                .isEmpty();

        ArgumentCaptor<DependencyResolutionRequest> requestCaptor =
                ArgumentCaptor.forClass(DependencyResolutionRequest.class);
        verify(projectDependenciesResolver, times(2)).resolve(requestCaptor.capture());
        List<DependencyResolutionRequest> requests = requestCaptor.getAllValues();
        assertThat(artifactIds(requests.get(0).getMavenProject()))
                .containsExactlyInAnyOrder("compile", "provided", "system");
        assertThat(artifactIds(requests.get(1).getMavenProject())).containsExactlyInAnyOrder("compile", "runtime");
        DependencyNode dependencyNode = new DefaultDependencyNode(
                new org.eclipse.aether.artifact.DefaultArtifact("groupId", "artifactId", "jar", "1.0"));
        assertThat(requests).allSatisfy(request -> {
            MavenProject graphProject = request.getMavenProject();
            assertThat(request.getRepositorySession()).isSameAs(mavenSession.getRepositorySession());
            assertThat(request.getResolutionFilter()).isNotNull();
            assertThat(request.getResolutionFilter().accept(dependencyNode, Collections.emptyList()))
                    .isFalse();
            assertThat(graphProject.getDependencies())
                    .noneMatch(dependency -> dependency.getArtifactId().endsWith("candidate"));
            assertThat(graphProject.getOriginalModel()).isSameAs(originalModel);
            assertThat(graphProject.getActiveProfiles()).containsExactly(activeProfile);
            assertThat(graphProject.getArtifacts()).containsExactly(resolvedState);
            assertThat(graphProject.getManagedVersionMap()).isSameAs(managedVersionMap);
            assertThat(graphProject.isExecutionRoot()).isTrue();
        });
    }

    private MavenProject projectWithRepositorySession(Artifact... dependencyArtifacts) {
        MavenProject project = new MavenProject();
        project.setDependencies(
                Arrays.stream(dependencyArtifacts).map(this::toDependency).collect(Collectors.toList()));
        RepositorySystemSession repositorySession = new DefaultRepositorySystemSession();
        when(mavenSession.getRepositorySession()).thenReturn(repositorySession);
        return project;
    }

    private DependencyResolutionResult dependencyGraph(String artifactId, String version) {
        return dependencyGraph(new DefaultDependencyNode(
                new org.eclipse.aether.artifact.DefaultArtifact("groupId", artifactId, "jar", version)));
    }

    private DependencyResolutionResult dependencyGraph(DependencyNode... children) {
        DependencyNode root = new DefaultDependencyNode((org.eclipse.aether.graph.Dependency) null);
        root.setChildren(Arrays.asList(children));
        DependencyResolutionResult result = mock(DependencyResolutionResult.class);
        when(result.getDependencyGraph()).thenReturn(root);
        return result;
    }

    private DependencyResolutionResult verboseConflictGraph(DependencyNode... children) throws Exception {
        DependencyResolutionResult result = dependencyGraph(children);
        DependencyNode root = result.getDependencyGraph();
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
        session.setConfigProperty(ConflictResolver.CONFIG_PROP_VERBOSE, true);
        Map<Object, Object> data = new HashMap<>();
        DependencyGraphTransformationContext context = new DependencyGraphTransformationContext() {
            @Override
            public RepositorySystemSession getSession() {
                return session;
            }

            @Override
            public Object get(Object key) {
                return data.get(key);
            }

            @Override
            public Object put(Object key, Object value) {
                return value == null ? data.remove(key) : data.put(key, value);
            }
        };
        new ConflictResolver(
                        new NearestVersionSelector(),
                        new JavaScopeSelector(),
                        new SimpleOptionalitySelector(),
                        new JavaScopeDeriver())
                .transformGraph(root, context);
        return result;
    }

    private boolean hasConflictLoser(DependencyNode node) {
        return node.getData().containsKey(ConflictResolver.NODE_DATA_WINNER)
                || node.getChildren().stream().anyMatch(this::hasConflictLoser);
    }

    private DependencyNode dependencyNode(String artifactId, DependencyNode... children) {
        return dependencyNode(artifactId, "1.0", children);
    }

    private DependencyNode dependencyNode(String artifactId, String version, DependencyNode... children) {
        org.eclipse.aether.artifact.Artifact artifact =
                new org.eclipse.aether.artifact.DefaultArtifact("groupId", artifactId, "jar", version);
        DefaultDependencyNode node =
                new DefaultDependencyNode(new org.eclipse.aether.graph.Dependency(artifact, "compile"));
        try {
            GenericVersionScheme versionScheme = new GenericVersionScheme();
            node.setVersion(versionScheme.parseVersion(version));
            node.setVersionConstraint(versionScheme.parseVersionConstraint(version));
        } catch (InvalidVersionSpecificationException exception) {
            throw new IllegalArgumentException(exception);
        }
        node.setChildren(Arrays.asList(children));
        return node;
    }

    private Artifact aJarArtifact(String artifactId, int... classAccess) throws IOException {
        return aJarArtifact(artifactId, "1.0", null, classAccess);
    }

    private Artifact aJarArtifact(String artifactId, String version, int... classAccess) throws IOException {
        return aJarArtifact(artifactId, version, null, classAccess);
    }

    private Artifact aServiceRegistrationJarArtifact(String artifactId) throws IOException {
        return aJarArtifact(artifactId, "1.0", "example.Service");
    }

    private Artifact aJarArtifact(String artifactId, String version, String service, int... classAccess)
            throws IOException {
        Artifact artifact = new DefaultArtifact(
                "groupId",
                artifactId,
                VersionRange.createFromVersion(version),
                Artifact.SCOPE_COMPILE,
                "jar",
                "",
                null);
        Path jar = tempDir.resolve(artifactId + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            if (service != null) {
                output.putNextEntry(new JarEntry("META-INF/services/" + service));
                output.write("example.Provider\n".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
            for (int index = 0; index < classAccess.length; index++) {
                String className = "example/" + artifactId.replace('-', '_') + index;
                ClassWriter writer = new ClassWriter(0);
                writer.visit(
                        Opcodes.V1_8,
                        Opcodes.ACC_SUPER | classAccess[index],
                        className,
                        null,
                        "java/lang/Object",
                        null);
                writer.visitEnd();
                output.putNextEntry(new JarEntry(className + ".class"));
                output.write(writer.toByteArray());
                output.closeEntry();
            }
        }
        artifact.setFile(jar.toFile());
        return artifact;
    }

    private Set<String> artifactIds(MavenProject project) {
        return project.getDependencies().stream().map(Dependency::getArtifactId).collect(Collectors.toSet());
    }

    private String managedVersion(MavenProject project, String artifactId) {
        return project.getDependencyManagement().getDependencies().stream()
                .filter(dependency -> artifactId.equals(dependency.getArtifactId()))
                .map(Dependency::getVersion)
                .findFirst()
                .orElse(null);
    }

    private Dependency toDependency(Artifact artifact) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(artifact.getGroupId());
        dependency.setArtifactId(artifact.getArtifactId());
        dependency.setVersion(artifact.getVersion());
        dependency.setScope(artifact.getScope());
        dependency.setType(artifact.getType());
        dependency.setClassifier(artifact.getClassifier());
        return dependency;
    }

    private Artifact aTestArtifact(String artifactId) {
        return aTestArtifact("groupId", artifactId);
    }

    private Artifact aTestArtifact(String groupId, String artifactId) {
        return aTestArtifact(groupId, artifactId, Artifact.SCOPE_COMPILE);
    }

    private Artifact aTestArtifactWithScope(String artifactId, String scope) {
        return aTestArtifact("groupId", artifactId, scope);
    }

    private Artifact aTestArtifactWithVersion(String artifactId, String version) {
        return new DefaultArtifact(
                "groupId",
                artifactId,
                VersionRange.createFromVersion(version),
                Artifact.SCOPE_COMPILE,
                "jar",
                "",
                null);
    }

    private Artifact aTestArtifact(String groupId, String artifactId, String scope) {
        return new DefaultArtifact(groupId, artifactId, VersionRange.createFromVersion("1.0"), scope, "jar", "", null);
    }

    private String dependencyVersion(MavenProject project, String artifactId) {
        return project.getDependencies().stream()
                .filter(dependency -> artifactId.equals(dependency.getArtifactId()))
                .map(Dependency::getVersion)
                .findFirst()
                .orElse(null);
    }
}
