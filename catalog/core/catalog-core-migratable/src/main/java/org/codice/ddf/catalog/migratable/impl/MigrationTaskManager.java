/**
 * Copyright (c) Codice Foundation
 * <p>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.catalog.migratable.impl;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import org.codice.ddf.migration.ExportMigrationException;
import org.codice.ddf.migration.MigrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import ddf.catalog.data.Result;

/**
 * Encapsulates all threading state for the catalog migration process and allows inspection of
 * its progress through a public API and the Guava asynchronous call-backs.
 * <p>
 * Repeat calls to exportMetacardQuery({@link List<Result>} results, {@link Long} exportGroupCount)
 * as needed to add file write tasks to the {@link java.util.concurrent.BlockingQueue} of the
 * {@link ExecutorService}. When finished, a call to exportFinish() is required to guarantee that
 * no errors occurred and that the executor will be shutdown properly.
 */
class MigrationTaskManager implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationTaskManager.class);

    private final ListeningExecutorService taskExecutor;

    private final MigrationFileWriter fileWriter;

    private final CatalogMigratableConfig catalogConfig;

    private boolean failureFlag;

    /**
     * The creation of a task manager requires the passing of migratable configurations so that
     * task behavior can be explicitly defined (i.e. where to export, how many metacards per file,
     * and other useful data). Also initializes the {@link MigrationFileWriter} and write directory.
     *
     * @param config The set of configuration options to use.
     */
    public MigrationTaskManager(final CatalogMigratableConfig config,
            final MigrationFileWriter fileWriter, final ExecutorService executor) {
        this.taskExecutor = MoreExecutors.listeningDecorator(executor);
        this.catalogConfig = config;
        this.fileWriter = fileWriter;
        this.failureFlag = false;
    }

    /**
     * Resources are automatically released by finishing the export process.
     *
     * @throws Exception If an error occurs during closing.
     */
    @Override
    public void close() throws Exception {
        this.exportFinish();
    }

    /**
     * Must be called after all invocations to exportMetacardQuery. This will attempt
     * to gracefully shutdown the executor service and handle any exceptions that might have
     * occured in the asynchronous threads.
     * <p>
     * If you are using {@link MigrationTaskManager}; that is, you have constructed an instance,
     * you must call this method to gracefully release resources and shutdown. Can also be used
     * within the scope of a try-with-resources block to utilize {@link AutoCloseable}.
     *
     * @throws MigrationException thrown if some of the some of the metacards couldn't be exported
     */
    public void exportFinish() throws MigrationException {
        LOGGER.debug("Attempting to shutdown catalog export");
        taskExecutor.shutdown();
        boolean isGracefulTermination = false;
        try {
            isGracefulTermination = taskExecutor.awaitTermination(1L, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            LOGGER.warn("Executor was interrupted during shutdown: " + e.getMessage(), e);
        } finally {
            if (!isGracefulTermination) {
                taskExecutor.shutdownNow();
            }
        }
    }

    /**
     * Creates a sublist from the given results and a destination file for metacards based on
     * the {@link CatalogMigratableConfig} object used by this task manager. An asynchronous
     * task is started for exporting the metacards and is deferred to the appropriate instance
     * of {@link MigrationFileWriter} for processing.
     *
     * @param results          The results of a catalog query that need to be exported.
     * @param exportGroupCount The group or page number of the query. This value is not monitored
     *                         or audited for repeats and is strictly for record keeping by naming
     *                         the resulting export files appropriately.
     * @throws MigrationException Thrown if one of the writing threads fails or throws an exception
     *                            itself.
     */
    public void exportMetacardQuery(final List<Result> results, long exportGroupCount)
            throws MigrationException {
        for (int i = 0; i < results.size(); i += catalogConfig.getExportCardsPerFile()) {
            final List<Result> fileResults = results.subList(i,
                    Math.min((i + catalogConfig.getExportCardsPerFile()), results.size()));

            final File exportFile = catalogConfig.getExportPath()
                    .resolve(makeFileName(exportGroupCount, i))
                    .toFile();

            CatalogWriterCallable writerCallable = createWriterCallable(exportFile,
                    fileResults,
                    fileWriter);

            ListenableFuture<Void> task = taskExecutor.submit(writerCallable);
            Futures.addCallback(task, createFutureCallback());

            if (failureFlag) {
                throw new ExportMigrationException("Error in file writing thread");
            }
        }
    }

    FutureCallback<Void> createFutureCallback() {
        return new FutureCallback<Void>() {
            @Override
            public void onSuccess(Void aVoid) {
                LOGGER.debug("File write complete");
            }

            @Override
            public void onFailure(@Nonnull Throwable throwable) {
                LOGGER.error("File writing thread threw an exception: ", throwable);
                taskExecutor.shutdownNow();
                failureFlag = true;
            }
        };
    }

    CatalogWriterCallable createWriterCallable(final File exportFile,
            final List<Result> fileResults, final MigrationFileWriter fileWriter) {
        return new CatalogWriterCallable(exportFile, fileResults, fileWriter);
    }

    String makeFileName(long queryPageNumber, int fileNumber) {
        return String.format("%s_%d_%d",
                catalogConfig.getExportFilePrefix(),
                queryPageNumber,
                fileNumber);
    }
}