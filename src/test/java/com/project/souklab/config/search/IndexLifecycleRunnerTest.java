package com.project.souklab.config.search;

import com.project.souklab.config.SearchProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexLifecycleRunnerTest {

    @Mock
    private SearchProperties searchProperties;

    @Mock
    private SearchIndexingService searchIndexingService;

    @InjectMocks
    private IndexLifecycleRunner runner;

    @Test
    void doesNothingWhenStartupSynchronizationIsDisabled() {
        when(searchProperties.isSyncOnStartup()).thenReturn(false);

        assertThatCode(() -> runner.run(new DefaultApplicationArguments(new String[0]))).doesNotThrowAnyException();
        verifyNoInteractions(searchIndexingService);
    }

    @Test
    void configuresAndStartsMassIndexer() {
        SearchProperties.MassIndexing massIndexing = new SearchProperties.MassIndexing();
        massIndexing.setThreadsToLoadObjects(2);
        massIndexing.setBatchSizeToLoadObjects(25);
        massIndexing.setIdFetchSize(50);

        when(searchProperties.isSyncOnStartup()).thenReturn(true);
        when(searchProperties.getMassIndexing()).thenReturn(massIndexing);
        when(searchIndexingService.start(any())).thenReturn(CompletableFuture.completedFuture(null));

        assertThatCode(() -> runner.run(new DefaultApplicationArguments(new String[0]))).doesNotThrowAnyException();
        verify(searchIndexingService).start(any());
    }

    @Test
    void absorbsAsynchronousMassIndexFailure() {
        SearchProperties.MassIndexing massIndexing = new SearchProperties.MassIndexing();
        massIndexing.setThreadsToLoadObjects(2);
        massIndexing.setBatchSizeToLoadObjects(25);
        massIndexing.setIdFetchSize(50);

        when(searchProperties.isSyncOnStartup()).thenReturn(true);
        when(searchProperties.getMassIndexing()).thenReturn(massIndexing);
        when(searchIndexingService.start(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("backend unavailable")));

        assertThatCode(() -> runner.run(new DefaultApplicationArguments(new String[0]))).doesNotThrowAnyException();
    }

    @Test
    void absorbsImmediateSearchSetupFailure() {
        SearchProperties.MassIndexing massIndexing = new SearchProperties.MassIndexing();
        massIndexing.setThreadsToLoadObjects(2);
        massIndexing.setBatchSizeToLoadObjects(25);
        massIndexing.setIdFetchSize(50);

        when(searchProperties.isSyncOnStartup()).thenReturn(true);
        when(searchProperties.getMassIndexing()).thenReturn(massIndexing);
        when(searchIndexingService.start(any())).thenThrow(new IllegalStateException("not available"));
        assertThatCode(() -> runner.run(new DefaultApplicationArguments(new String[0]))).doesNotThrowAnyException();
    }
}
