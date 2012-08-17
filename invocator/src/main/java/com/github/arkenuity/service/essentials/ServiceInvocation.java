package com.github.arkenuity.service.essentials;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.yammer.metrics.Metrics.newTimer;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.lang.annotation.Annotation;
import java.net.ConnectException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Clock;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Timer;
/**
 * A service invocation executor utility, which provides the following:
 * <p>
 * <li> Instrument specific aspects (time duration, success/failure times) etc as dictated by the caller.
 * <li> Enforce conformance (maxWaitTime, maxRetries etc) an aspects provided by the client.
 *
 * <p>
 *
 * <pre>
 *    UserProfile profile = ServiceInvocation.execute(new Callable<UserProfile> {
 *        &#064Conform(retryCount=2, maxTimeWait=100, maxWaitTimeUnit=TimeUnit.MILLISECONDS)
 *        &#064Instrumented(clazz=UserProfileServiceProxy.class, method="byProfileId")
 *        public UserProfile call() {
 *            profileService.byProfile(profileId);
 *        }});
 *
 * </pre>
 *
 * <p>
 * Note: This utility uses a  {@link ExecutorService#newCachedThreadPool()} and process the tasks submitted through the
 * Callable using the threads in the pool.
 *
 * @author <a href="mailto:arkenuity@gmail.com">Rajesh Kumar Arcot</a>
 */
public final class ServiceInvocation<T> {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceInvocation.class);

    public static <T> T execute(final Callable<T> callable) {
        checkNotNull(callable, "A non null Callable instance should be passed.");
        return Execution.on(callable).execute();
    }

    private static abstract class Execution<T> {
        private final Instrumentation instrumentation;
        protected final Callable<T> callable;
        private final ListeningExecutorService service =
                MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());

        protected Execution(final Callable<T> callable) {
            this.callable = callable;
            instrumentation = Instrumentation.on(callable);
        }

        private static <T> Execution<T> on(final Callable<T> callable) {
            final Optional<Conform> conformance = annotation(callable, Conform.class);
            return conformance.isPresent() ? new ConformedExecution<T>(callable, conformance.get()) :
                new SimpleExecution<T>(callable);
        }

        protected Future<T> execute(final Callable<T> callable) {
            // Start the instrumentation (note only desired instrumentation will kick in, as described by annotation)
            instrumentation.start();

            final ListenableFuture<T> future = service.submit(callable);
            Futures.addCallback(future, new FutureCallback<T>() {
                @Override
                public void onSuccess(final T result) {
                    instrumentation.trackSuccess();
                }
                @Override
                public void onFailure(final Throwable th) {
                    // Perform more fine grained tracking
                    instrumentation.trackFailure(th);
                }
            });

            return future;
        }

        protected T simpleGet(final Future<T> future) {
            return Futures.get(future, ServiceInvocationException.class);
        }

        abstract T execute();
    }

    private static class SimpleExecution<T> extends Execution<T> {
        private SimpleExecution(final Callable<T> callable) {
            super(callable);
        }

        @Override
        T execute() {
            return simpleGet(execute(callable));
        }
    }

    private static class ConformedExecution<T> extends Execution<T> {
        private final int maxAttempts;
        private final long maxWaitTime;
        private final TimeUnit maxWaitTimeUnit;

        private ConformedExecution(final Callable<T> callable, final Conform conformance) {
            super(callable);
            this.maxAttempts = conformance.retryCount() == 0 ? 1 : conformance.retryCount();
            this.maxWaitTime = conformance.maxWaitTime();
            this.maxWaitTimeUnit = conformance.maxWaitTimeUnit();
        }

        @Override
        T execute() {
            final int attempt = 1;
            Optional<Throwable> lastThrowable = Optional.absent();
            while (attempt <= maxAttempts) {
                LOG.debug("Execution attempt {}", attempt);
                try {
                    return conform(execute(callable));
                } catch (final Throwable th) {
                    lastThrowable = Optional.of(th);
                }
            }
            // failed execution, throw the last exception/error
            LOG.error(String.format("Failed to successful execute for {} attempts", maxAttempts), lastThrowable);
            throw new RuntimeException(lastThrowable.get());
         }

        private T conform(final Future<T> future) {
            if (maxWaitTime > 0) {
                return Futures.get(future, maxWaitTime, maxWaitTimeUnit, ServiceInvocationException.class);
            } else {
                return Futures.get(future, ServiceInvocationException.class);
            }
        }
    }


    private static abstract class Instrumentation {

        abstract void start();

        abstract void trackSuccess();

        abstract void trackFailure(Throwable t);

        protected static Instrumentation on(final Callable<?> callable) {
            final Optional<Instrumented> instrumented = annotation(callable, Instrumented.class);
            return instrumented.isPresent() ? new DesiredInstrumentation(instrumented.get()) :
                NoOpInstrumentation.INSTANCE;
        }
    }

    private static class NoOpInstrumentation extends Instrumentation {

        private static final NoOpInstrumentation INSTANCE = new NoOpInstrumentation();

        NoOpInstrumentation() {}

        @Override
        void start() {}

        @Override
        void trackSuccess() {}

        @Override
        void trackFailure(final Throwable th) {}
    }


    private static class DesiredInstrumentation extends Instrumentation {
        private final ImmutableList<Instrumentation> instrumentors;

        public DesiredInstrumentation(final Instrumented instrumented) {
            final ImmutableList.Builder<Instrumentation> builder = ImmutableList.builder();
            addTimeInstrumenation(instrumented, builder);
            addLogInstrumentation(instrumented, builder);
            addCounterInstrumentation(instrumented, builder);
            instrumentors = builder.build();
        }

        @Override
        void start() {
            for (final Instrumentation instrumentation : instrumentors) {
                instrumentation.start();
            }
        }

        @Override
        void trackSuccess() {
            for (final Instrumentation instrumentation : instrumentors) {
                instrumentation.trackSuccess();
            }
        }

        @Override
        void trackFailure(final Throwable th) {
            for (final Instrumentation instrumentation : instrumentors) {
                instrumentation.trackFailure(th);
            }
        }

        private void addLogInstrumentation(final Instrumented instrumented,
                                           final ImmutableList.Builder<Instrumentation> builder) {
            if (instrumented.logged()) {
                builder.add(new LogInstrumentation(instrumented));
            }
        }

        private void addTimeInstrumenation(final Instrumented instrumented,
                                           final ImmutableList.Builder<Instrumentation> builder) {
            if (instrumented.timed()) {
                builder.add(new TimeInstrumentation(instrumented));
            }
        }

        private void addCounterInstrumentation(final Instrumented instrumented,
                                               final ImmutableList.Builder<Instrumentation> builder) {
            if (instrumented.count()) {
                builder.add(new CountInstrumentation(instrumented));
            }
        }
    }

    private static class TimeInstrumentation extends Instrumentation {

        private long start;
        private final Instrumented instrumented;

        private TimeInstrumentation(final Instrumented instrumented) {
            this.instrumented = instrumented;
        }

        @Override
        void start() {
            start = Clock.defaultClock().tick();
        }

        @Override
        void trackSuccess() {
            timer(instrumented, "Failure").update(Clock.defaultClock().tick() - start, NANOSECONDS);
        }

        @Override
        void trackFailure(final Throwable th) {
            timer(instrumented, "Success").update(Clock.defaultClock().tick() - start, NANOSECONDS);
        }

        private Timer timer(final Instrumented instrumented, final String name) {
            return newTimer(instrumented.getClass(), name, instrumented.method(), MILLISECONDS, MINUTES);
        }
    }

    private static class CountInstrumentation extends Instrumentation {
        private final Instrumented instrumented;

        private CountInstrumentation(final Instrumented instrumented) {
            this.instrumented = instrumented;
        }

        @Override
        void start() {

        }

        @Override
        void trackSuccess() {
            counter(instrumented, "Success").inc();
        }

        @Override
        void trackFailure(final Throwable th) {
            Throwables.getRootCause(th);
            if (ConnectException.class.isAssignableFrom(th.getClass())) {// Http connection timeouts
                counter(instrumented, "Connect-Failure").inc();
            }
            counter(instrumented, "Failure").inc();
        }

        private Counter counter(final Instrumented instrumented, final String name) {
            return Metrics.newCounter(instrumented.getClass(), name, instrumented.method());
        }

    }

    private static class LogInstrumentation extends Instrumentation {
        private final Instrumented instrumented;

        private LogInstrumentation(final Instrumented instrumented) {
            this.instrumented = instrumented;
        }

        @Override
        void start() {
            LOG.info("Executing {}.{} call", instrumented.getClass(), instrumented.method());
        }

        @Override
        void trackSuccess() {
            LOG.info("{}.{} call Succeeded!", instrumented.getClass(), instrumented.method());
        }

        @Override
        void trackFailure(final Throwable th) {
            LOG.warn("{}.{} call FAILED.", instrumented.getClass(), instrumented.method());
            LOG.debug(String.format("%s.%s call FAILED, cause: ", instrumented.getClass(), instrumented.method()), th);
        }
    }


    private static <V extends Annotation> Optional<V> annotation(final Callable<?> callable, final Class<V> clazz) {
        try {
            return Optional.fromNullable(callable.getClass().getMethod("call").getAnnotation(clazz));
        } catch (final Exception e) { // Should not happen
            return Optional.absent();
        }
    }

}
