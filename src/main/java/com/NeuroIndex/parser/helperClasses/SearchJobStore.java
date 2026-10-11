package com.NeuroIndex.parser.helperClasses;

import com.NeuroIndex.parser.service.impl.SearchServiceImpl;
import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@Component
@Log4j2
public class SearchJobStore {

    private static final int MAX_JOBS = 200;

    private static final long RETENTION_NANOS =
            TimeUnit.MINUTES.toNanos(5);

    private static final long WORK_BUDGET_NANOS =
            TimeUnit.SECONDS.toNanos(15);

    private final ExecutorService executor;

    private final Object lock = new Object();

    private final LinkedHashMap<UUID, Job> jobs =
            new LinkedHashMap<>();

    public SearchJobStore(
            @Qualifier("searchGraphExecutor")
            ExecutorService executor
    ) {
        this.executor = executor;
    }

    public SearchTicket start(
            Long userId,
            List<SearchServiceImpl.MessageSearchResponse> initialResults,
            boolean hasGraphWork,
            Function<JobControl,
                    List<SearchServiceImpl.MessageSearchResponse>> work
    ) {
        Job job = new Job(
                userId,
                initialResults,
                !hasGraphWork
        );

        synchronized (lock) {
            cleanLocked(System.nanoTime());

            while (jobs.size() >= MAX_JOBS) {
                Iterator<Job> iterator = jobs.values().iterator();

                Job oldest = iterator.next();

                iterator.remove();
                oldest.expire();
            }

            jobs.put(job.id, job);
        }

        SearchTicket initialTicket = job.state.get();

        if (!hasGraphWork) {
            return initialTicket;
        }

        try {
            Future<?> task = executor.submit(
                    () -> execute(job, work)
            );

            job.attach(task);

        } catch (RejectedExecutionException exception) {
            job.fail(
                    "Graph search is busy; initial results remain available."
            );

            log.warn(
                    "Graph task rejected: userId={}, searchId={}",
                    userId,
                    job.id
            );

            return job.state.get();
        }

        // No Future.get(): return the initial snapshot.
        return initialTicket;
    }

    public SearchTicket get(
            Long userId,
            UUID searchId
    ) {
        if (userId == null || userId <= 0 || searchId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid search reference"
            );
        }

        synchronized (lock) {
            cleanLocked(System.nanoTime());

            Job job = jobs.get(searchId);

            if (job == null
                    || !Objects.equals(job.userId, userId)) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Search not found or expired"
                );
            }

            return job.state.get();
        }
    }

    private void execute(
            Job job,
            Function<JobControl,
                                List<SearchServiceImpl.MessageSearchResponse>> work
    ) {
        long started = System.nanoTime();

        log.info(
                "Graph worker started: userId={}, searchId={}, queueWaitMs={}",
                job.userId,
                job.id,
                TimeUnit.NANOSECONDS.toMillis(
                        started - job.createdNanos
                )
        );

        JobControl control = new JobControl(
                job.deadlineNanos
        );

        try {
            control.checkpoint();

            List<SearchServiceImpl.MessageSearchResponse> results =
                    work.apply(control);

            control.checkpoint();

            job.complete(results);

        } catch (BudgetExceeded exception) {
            job.fail(
                    "Graph refinement exceeded its time budget; "
                            + "initial results remain available."
            );

            log.warn(
                    "Graph budget exceeded: userId={}, searchId={}",
                    job.userId,
                    job.id
            );

        } catch (CancellationException exception) {
            job.fail(
                    "Graph refinement was cancelled; "
                            + "initial results remain available."
            );

            log.info(
                    "Graph task cancelled: userId={}, searchId={}",
                    job.userId,
                    job.id
            );

        } catch (Exception exception) {
            job.fail(
                    "Graph refinement failed; "
                            + "initial results remain available."
            );

            log.error(
                    "Graph refinement failed: userId={}, searchId={}",
                    job.userId,
                    job.id,
                    exception
            );

        } finally {
            log.info(
                    "Graph worker finished: userId={}, searchId={}, elapsedMs={}",
                    job.userId,
                    job.id,
                    TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - started
                    )
            );
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void cleanup() {
        synchronized (lock) {
            cleanLocked(System.nanoTime());
        }

        if (executor instanceof ThreadPoolExecutor pool) {
            pool.purge();
        }
    }

    private void cleanLocked(long now) {
        Iterator<Job> iterator = jobs.values().iterator();

        while (iterator.hasNext()) {
            Job job = iterator.next();

            if (now - job.createdNanos >= RETENTION_NANOS) {
                iterator.remove();
                job.expire();

            } else if (now - job.deadlineNanos >= 0) {
                job.fail(
                        "Graph refinement exceeded its time budget; "
                                + "initial results remain available."
                );

                if (job.state.get().status() == Status.FAILED) {
                    job.cancelTask();
                }
            }
        }
    }

    public record JobControl(long deadlineNanos) {

        public void checkpoint() {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException(
                        "Graph refinement interrupted"
                );
            }

            if (System.nanoTime() - deadlineNanos >= 0) {
                throw new BudgetExceeded();
            }
        }
    }

    private static final class BudgetExceeded
            extends RuntimeException {
    }

    private static final class Job {

        private final UUID id = UUID.randomUUID();

        private final Long userId;

        private final long createdNanos = System.nanoTime();

        private final long deadlineNanos =
                createdNanos + WORK_BUDGET_NANOS;

        private final AtomicReference<SearchTicket> state;

        private volatile Future<?> task;
        private volatile boolean expired;

        private Job(
                Long userId,
                List<SearchServiceImpl.MessageSearchResponse> initialResults,
                boolean completed
        ) {
            this.userId = userId;

            this.state = new AtomicReference<>(
                    new SearchTicket(
                            id,
                            completed
                                    ? Status.COMPLETED
                                    : Status.RUNNING,
                            List.copyOf(initialResults),
                            null
                    )
            );
        }

        private void attach(Future<?> future) {
            task = future;

            if (expired
                    || state.get().status() == Status.FAILED) {
                future.cancel(true);
            }
        }

        private void complete(
                List<SearchServiceImpl.MessageSearchResponse> results
        ) {
            SearchTicket previous = state.get();

            if (!expired && previous.status() == Status.RUNNING) {
                state.compareAndSet(
                        previous,
                        new SearchTicket(
                                id,
                                Status.COMPLETED,
                                List.copyOf(results),
                                null
                        )
                );
            }
        }

        private void fail(String error) {
            SearchTicket previous = state.get();

            if (previous.status() == Status.RUNNING) {
                state.compareAndSet(
                        previous,
                        new SearchTicket(
                                id,
                                Status.FAILED,
                                previous.results(),
                                error
                        )
                );
            }
        }

        private void expire() {
            expired = true;
            cancelTask();
        }

        private void cancelTask() {
            Future<?> future = task;

            if (future != null) {
                future.cancel(true);
            }
        }
    }

    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED
    }

    public record SearchTicket(
            UUID searchId,
            Status status,
            List<SearchServiceImpl.MessageSearchResponse> results,
            String error
    ) {
    }
}
