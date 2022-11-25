package io.javaoperatorsdk.operator.api.config;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExecutorServiceManager {
  private static final Logger log = LoggerFactory.getLogger(ExecutorServiceManager.class);
  private static ExecutorServiceManager instance;
  private final ExecutorService executor;
  private final ExecutorService workflowExecutor;
  private final int terminationTimeoutSeconds;

  private ExecutorServiceManager(ExecutorService executor, ExecutorService workflowExecutor,
      int terminationTimeoutSeconds) {
    this.executor = new InstrumentedExecutorService(executor);
    this.workflowExecutor = new InstrumentedExecutorService(workflowExecutor);
    this.terminationTimeoutSeconds = terminationTimeoutSeconds;
  }

  public static void init() {
    if (instance == null) {
      final var configuration = ConfigurationServiceProvider.instance();
      final var executorService = configuration.getExecutorService();
      final var workflowExecutorService = configuration.getWorkflowExecutorService();
      instance = new ExecutorServiceManager(executorService, workflowExecutorService,
          configuration.getTerminationTimeoutSeconds());
      log.debug(
          "Initialized ExecutorServiceManager executor: {}, workflow executor: {}, timeout: {}",
          executorService.getClass(),
          workflowExecutorService.getClass(),
          configuration.getTerminationTimeoutSeconds());
    } else {
      log.debug("Already started, reusing already setup instance!");
    }
  }

  public synchronized static void stop() {
    if (instance != null) {
      instance.doStop();
    }
    // make sure that we remove the singleton so that the thread pool is re-created on next call to
    // start
    instance = null;
  }

  public synchronized static ExecutorServiceManager instance() {
    if (instance == null) {
      // provide a default configuration if none has been provided by init
      init();
    }
    return instance;
  }

  public static <T> void executeAndWaitForAllToComplete(Stream<T> stream,
      Function<T, Void> task, Function<T, String> threadNamer) {
    final var instrumented = new InstrumentedExecutorService(
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()));

    try {
      instrumented.invokeAll(stream.parallel().map(item -> (Callable<Void>) () -> {
        // change thread name for easier debugging
        final var thread = Thread.currentThread();
        final var name = thread.getName();
        thread.setName(threadNamer.apply(item));
        try {
          task.apply(item);
          return null;
        } finally {
          // restore original name
          thread.setName(name);
        }
      }).collect(Collectors.toList()));
      shutdown(instrumented);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public ExecutorService executorService() {
    return executor;
  }

  public ExecutorService workflowExecutorService() {
    return workflowExecutor;
  }

  private void doStop() {
    try {
      log.debug("Closing executor");
      shutdown(executor);
      shutdown(workflowExecutor);
    } catch (InterruptedException e) {
      log.debug("Exception closing executor: {}", e.getLocalizedMessage());
      Thread.currentThread().interrupt();
    }
  }

  private static void shutdown(ExecutorService executorService) throws InterruptedException {
    executorService.shutdown();
    if (!executorService.awaitTermination(instance().terminationTimeoutSeconds, TimeUnit.SECONDS)) {
      executorService.shutdownNow(); // if we timed out, waiting, cancel everything
    }
  }

  private static class InstrumentedExecutorService implements ExecutorService {
    private final boolean debug;
    private final ExecutorService executor;

    private InstrumentedExecutorService(ExecutorService executor) {
      if (executor == null) {
        throw new NullPointerException();
      }
      this.executor = executor;
      debug = Utils.debugThreadPool();
    }

    @Override
    public void shutdown() {
      if (debug) {
        Thread.dumpStack();
      }
      executor.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
      return executor.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
      return executor.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return executor.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return executor.awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      return executor.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
      return executor.submit(task, result);
    }

    @Override
    public Future<?> submit(Runnable task) {
      return executor.submit(task);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
        throws InterruptedException {
      return executor.invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout,
        TimeUnit unit) throws InterruptedException {
      return executor.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException {
      return executor.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      return executor.invokeAny(tasks, timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
      executor.execute(command);
    }
  }
}
