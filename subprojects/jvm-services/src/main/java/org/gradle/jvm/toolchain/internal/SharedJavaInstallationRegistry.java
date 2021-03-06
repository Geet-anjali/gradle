/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.jvm.toolchain.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.CallableBuildOperation;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;


public class SharedJavaInstallationRegistry {
    private final BuildOperationExecutor executor;
    private final Supplier<Set<InstallationLocation>> installations;
    private final Logger logger;

    @Inject
    public SharedJavaInstallationRegistry(List<InstallationSupplier> suppliers, BuildOperationExecutor executor) {
        this(suppliers, Logging.getLogger(SharedJavaInstallationRegistry.class), executor);
    }

    private SharedJavaInstallationRegistry(List<InstallationSupplier> suppliers, Logger logger, BuildOperationExecutor executor) {
        this.logger = logger;
        this.executor = executor;
        this.installations = Suppliers.memoize(() -> collectInBuildOperation(suppliers));
    }

    @VisibleForTesting
    static SharedJavaInstallationRegistry withLogger(List<InstallationSupplier> suppliers, Logger logger, BuildOperationExecutor executor) {
        return new SharedJavaInstallationRegistry(suppliers, logger, executor);
    }

    private Set<InstallationLocation> collectInBuildOperation(List<InstallationSupplier> suppliers) {
        return executor.call(new ToolchainDetectionBuildOperation(() -> collectInstallations(suppliers)));
    }

    public Set<InstallationLocation> listInstallations() {
        return installations.get();
    }

    private Set<InstallationLocation> collectInstallations(List<InstallationSupplier> suppliers) {
        return suppliers.stream()
            .map(InstallationSupplier::get)
            .flatMap(Set::stream)
            .filter(this::installationExists)
            .map(this::canonicalize)
            .filter(distinctByKey(InstallationLocation::getLocation))
            .collect(Collectors.toSet());
    }

    boolean installationExists(InstallationLocation installationLocation) {
        File file = installationLocation.getLocation();
        if (!file.exists()) {
            logger.warn("Directory {} used for java installations does not exist", installationLocation.getDisplayName());
            return false;
        }
        if (!file.isDirectory()) {
            logger.warn("Path for java installation {} points to a file, not a directory", installationLocation.getDisplayName());
            return false;
        }
        return true;
    }

    private InstallationLocation canonicalize(InstallationLocation location) {
        final File file = location.getLocation();
        try {
            final File canonicalFile = file.getCanonicalFile();
            return new InstallationLocation(canonicalFile, location.getSource());
        } catch (IOException e) {
            throw new GradleException(String.format("Could not canonicalize path to java installation: %s.", file), e);
        }
    }

    public static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Set<Object> seen = ConcurrentHashMap.newKeySet();
        return t -> seen.add(keyExtractor.apply(t));
    }


    private static class ToolchainDetectionBuildOperation implements CallableBuildOperation<Set<InstallationLocation>> {
        private final Callable<Set<InstallationLocation>> detectionStrategy;

        public ToolchainDetectionBuildOperation(Callable<Set<InstallationLocation>> detectionStrategy) {
            this.detectionStrategy = detectionStrategy;
        }

        @Override
        public Set<InstallationLocation> call(BuildOperationContext context) throws Exception {
            return detectionStrategy.call();
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            return BuildOperationDescriptor
                .displayName("Toolchain detection")
                .progressDisplayName("Detecting local java toolchains");
        }
    }

}
