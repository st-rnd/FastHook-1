package com.ns.greg.library.fasthook;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import com.ns.greg.library.fasthook.annotaion.ObserverOn;
import com.ns.greg.library.fasthook.callback.RunCallback;
import com.ns.greg.library.fasthook.exception.EasyException;
import com.ns.greg.library.fasthook.functions.BaseRun;
import com.ns.greg.library.fasthook.functions.EasyRun0;
import com.ns.greg.library.fasthook.functions.EasyRun1;
import com.ns.greg.library.fasthook.functions.EasyRun2;
import com.ns.greg.library.fasthook.observer.BaseObserver;
import com.ns.greg.library.fasthook.observer.IThreadManagerInterface;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author Gregory
 * @since 2016/5/5
 */
public abstract class BaseThreadManager<T extends ThreadPoolExecutor>
    implements IThreadManagerInterface.ActionSubject {

  // A queue of Runnable for the Thread pool
  private final BlockingQueue<Runnable> blockingQueue;
  // A queue of ThreadManager tasks. Tasks are handed to a ThreadPool.
  private final Queue<BaseThreadTask> taskQueue;
  // A managed pool of test Thread
  private final T threadPool;
  // Monitor thread
  private PoolMonitorThread monitorThread;
  // Is log
  private boolean isLog = true;
  // The observers (thread-safe)
  private final List<BaseObserver<BaseRun>> observers = new CopyOnWriteArrayList<>();
  // Handler
  private final ThreadHandler handler;

  protected BaseThreadManager() {
    // Creates a work queue for the set of of task objects,
    // using a linked list queue that blocks when the queue is empty.
    taskQueue = new LinkedBlockingQueue<>();
    // Creates a new pool type according to T
    threadPool = createThreadPool();
    // Gets work queue form thread pool
    blockingQueue = threadPool.getQueue();
    // Creates a handler for subscribe at UI thread
    handler = new ThreadHandler(this, Looper.getMainLooper());
  }

  /**
   * Create thread pool type according to the T,
   * customize or create it by {@link ThreadExecutorFactory}
   */
  protected abstract T createThreadPool();

  /**
   * Handle the thread state {@link BaseRunnable#COMPLETE_STATUS}, {@link
   * BaseRunnable#EXCEPTION_STATUS}
   */
  @SuppressWarnings("unchecked") void handleState(BaseThreadTask baseThreadTask, int state) {
    BaseRunnable runnable = baseThreadTask.getRunnableObject();
    switch (state) {
      case BaseRunnable.COMPLETE_STATUS:
        if (runnable.getObserverOn() == HookPlugins.UI_THREAD) {
          handler.obtainMessage(state, baseThreadTask).sendToTarget();
        } else {
          BaseRun completeRun = runnable.getResult();
          if (completeRun != null) {
            notifyObserversOnCompleted(completeRun);
          }

          RunCallback completeCallback = runnable.getRunCallback();
          if (completeCallback != null) {
            completeCallback.done(completeRun, null);
          }

          recycleTask(baseThreadTask);
        }

        break;

      case BaseRunnable.EXCEPTION_STATUS:
        if (runnable.getObserverOn() == HookPlugins.UI_THREAD) {
          handler.obtainMessage(state, baseThreadTask).sendToTarget();
        } else {
          BaseRun exceptionRun = runnable.getResult();
          if (exceptionRun != null) {
            notifyObserversOnError(exceptionRun);
          }

          RunCallback exceptionCallback = runnable.getRunCallback();
          if (exceptionCallback != null) {
            exceptionCallback.done(exceptionRun, new EasyException(EasyException.INTERRUPTED_ERROR,
                runnable.getThreadName() + " got exception."));
          }

          recycleTask(baseThreadTask);
        }

        break;

      default:
        break;
    }
  }

  protected abstract BaseThreadTask createBaseThreadTask(BaseRunnable job);

  /**
   * Build the task with runnable using {@link Builder}
   *
   * @param runnable runnable object
   * @param <U> the callback interface
   */
  public <U extends BaseRun> Builder<U> addTask(BaseRunnable<U> runnable) {
    return new Builder<>(this, runnable);
  }

  /**
   * Start the task
   *
   * @param builder the task builder
   * @param <U> the callback interface
   */
  private <U extends BaseRun> BaseThreadTask startTask(Builder<U> builder) {
    BaseThreadTask threadTask = null;
    if (builder.useRecycleTask) {
      // Gets a task from the pool of tasks, returning null if the pool is empty
      threadTask = taskQueue.poll();
    }

    // If the queue was empty, create a new task instead
    if (threadTask == null) {
      threadTask = createBaseThreadTask(builder.runnable);
    } else {
      threadTask.setRunnableObject(builder.runnable);
    }

    builder.runnable.setExecuteStartTime(0L);
    builder.runnable.setLog(isLog);
    builder.runnable.setRunnableObjectMethods(threadTask);
    threadTask.initializeTask(this);
    if (threadPool instanceof ScheduledThreadPoolExecutor) {
      builder.runnable.setExecuteStartTime(System.currentTimeMillis());
      ((ScheduledThreadPoolExecutor) threadPool).schedule(builder.runnable, builder.delayTime,
          TimeUnit.MILLISECONDS);
    } else {
      builder.runnable.setDelayTime(builder.delayTime);
      threadPool.execute(builder.runnable);
    }

    return threadTask;
  }

  /**
   * Interrupted thread task
   *
   * @param threadTask target task
   */
  public void removeWork(BaseThreadTask threadTask) {
    // If the Thread object still exists
    if (threadTask != null) {
      //Locks on this class to ensure that other processes aren't mutating Threads.
      synchronized (this) {
        // Gets the Thread that the downloader task is running on
        Thread thread = threadTask.getThread();
        // If the Thread exists, posts an interrupt to it
        if (thread != null) {
          thread.interrupt();
          // Removes the runnable from the ThreadPool. This opens a Thread in the
          // ThreadPool's work queue, allowing a task in the queue to start.
          threadPool.remove(threadTask.getRunnableObject());
        }
      }
    }
  }

  /**
   * Cancel the thread that is ing awaiting in queue
   */
  public void cancelAwaitingWork() {
    // Creates an array of tasks that's the same size as the task work queue
    BaseRunnable[] taskArray = new BaseRunnable[blockingQueue.size()];
    // Populates the array with the task objects in the queue
    blockingQueue.toArray(taskArray);
    // Locks on the singleton to ensure that other processes aren't mutating Threads, then
    // iterates over the array of tasks and interrupts the task's current Thread.
    synchronized (this) {
      // Iterates over the array of tasks
      for (BaseRunnable aTaskArray : taskArray) {
        blockingQueue.remove(aTaskArray);
      }
    }
  }

  /**
   * Recycles tasks by calling their internal recycle() method and then putting them back into
   * the task queue.
   *
   * @param threadTask The task to recycle
   */
  private void recycleTask(BaseThreadTask threadTask) {
    // Puts the task object back into the queue for re-use.
    taskQueue.offer(threadTask);
  }

  /**
   * Is thread pool shut down
   */
  public boolean isShutdown() {
    return threadPool.isShutdown();
  }

  /**
   * Shuts down thread pool
   *
   * @param awaitTime to wait thread pool terminate
   */
  public void shutdownAndAwaitTermination(long awaitTime) {
    // At least wait 1 second
    if (awaitTime < 1000) {
      awaitTime = 1000;
    }

    cancelAwaitingWork();
    threadPool.shutdown();
    try {
      if (!threadPool.awaitTermination(awaitTime, TimeUnit.MILLISECONDS)) {
        cancelAwaitingWork();
        threadPool.shutdownNow();
      }
    } catch (InterruptedException e) {
      cancelAwaitingWork();
      threadPool.shutdownNow();
    }
  }

  /**
   * Gets the active count in thread pool
   */
  public int getActiveCount() {
    return threadPool.getActiveCount();
  }

  /**
   * Gets the completed task count in thread pool
   */
  public long getCompletedTaskCount() {
    return threadPool.getCompletedTaskCount();
  }

  /**
   * Gets the task count in thread pool
   */
  public long getTaskCount() {
    return threadPool.getTaskCount();
  }

  /**
   * Creates monitor that monitor thread pool
   */
  public void createMonitor(int period) {
    if (monitorThread == null) {
      ExecutorService cachedThreadPool = Executors.newSingleThreadExecutor();
      monitorThread = new PoolMonitorThread(threadPool, period);
      cachedThreadPool.execute(monitorThread);
    }
  }

  /**
   * Sets monitor is enable or not
   */
  public void setMonitorEnable(boolean enable) {
    if (monitorThread != null) {
      monitorThread.setMonitor(enable);
    }
  }

  /**
   * Print thread manager log
   */
  public void setLog(boolean log) {
    isLog = log;
  }

  @Override public void addObserver(BaseObserver<BaseRun> observer) {
    synchronized (observers) {
      observers.add(observer);
    }
  }

  @Override public void removeObserver(BaseObserver<BaseRun> observer) {
    synchronized (observers) {
      int i = observers.indexOf(observer);
      if (i >= 0) {
        observers.remove(i);
      }
    }
  }

  @Override public void clearObservers() {
    observers.clear();
  }

  @Override public void notifyObserversOnCompleted(BaseRun data) {
    synchronized (observers) {
      Iterator<BaseObserver<BaseRun>> iterator = observers.iterator();
      while (iterator.hasNext()) {
        iterator.next().onCompleted(data);
      }
    }
  }

  @Override public void notifyObserversOnError(BaseRun data) {
    synchronized (observers) {
      Iterator<BaseObserver<BaseRun>> iterator = observers.iterator();
      while (iterator.hasNext()) {
        iterator.next().onError(data);
      }
    }
  }

  private static class ThreadHandler extends Handler {

    private final BaseThreadManager instance;

    ThreadHandler(BaseThreadManager reference, Looper looper) {
      super(looper);
      WeakReference<BaseThreadManager> weakReference = new WeakReference<>(reference);
      instance = weakReference.get();
    }

    @SuppressWarnings("unchecked") @Override public void handleMessage(Message inputMessage) {
      BaseThreadTask threadTask = (BaseThreadTask) inputMessage.obj;
      BaseRunnable runnable = threadTask.getRunnableObject();
      switch (inputMessage.what) {
        case BaseRunnable.COMPLETE_STATUS:
          BaseRun completeRun = runnable.getResult();
          if (completeRun != null) {
            instance.notifyObserversOnCompleted(completeRun);
          }

          RunCallback completeCallback = runnable.getRunCallback();
          if (completeCallback != null) {
            completeCallback.done(completeRun, null);
          }

          instance.recycleTask(threadTask);
          break;

        case BaseRunnable.EXCEPTION_STATUS:
          BaseRun exceptionRun = runnable.getResult();
          if (exceptionRun != null) {
            instance.notifyObserversOnError(exceptionRun);
          }

          RunCallback exceptionCallback = runnable.getRunCallback();
          if (exceptionCallback != null) {
            exceptionCallback.done(exceptionRun, new EasyException(EasyException.INTERRUPTED_ERROR,
                runnable.getThreadName() + " got exception."));
          }

          instance.recycleTask(threadTask);
          break;

        default:
          break;
      }
    }
  }

  public static final class Builder<U extends BaseRun> {

    private BaseThreadManager instance;
    private BaseRunnable<U> runnable;
    private long delayTime;
    private RunCallback<U> callback;
    private int observerOn;
    private boolean useRecycleTask;

    private Builder(BaseThreadManager reference, BaseRunnable<U> runnable) {
      this.instance = new WeakReference<>(reference).get();
      this.runnable = runnable;
      this.delayTime = 0;
      this.callback = null;
      this.observerOn = HookPlugins.UI_THREAD;
      this.useRecycleTask = true;
    }

    /**
     * Sets delay time (ms)
     *
     * @param delayTime runnable delay time
     */
    public Builder<U> addDelayTime(long delayTime) {
      this.delayTime = delayTime;
      return this;
    }

    /**
     * Task callback, choose any callback which implements {@link BaseRun}
     *
     * @param callback {@link EasyRun0}, {@link EasyRun1}, {@link EasyRun2}
     */
    public Builder<U> addCallback(RunCallback<U> callback) {
      this.callback = callback;
      return this;
    }

    /**
     * Decides the observer or callback will received the result at
     * which thread (UI / current)
     *
     * @param observerOn {@link HookPlugins#UI_THREAD}, {@link HookPlugins#CURRENT_THREAD}
     */
    public Builder<U> observerOn(@ObserverOn int observerOn) {
      this.observerOn = observerOn;
      return this;
    }

    /**
     * Decides the manager should using the task which is poll out
     * from pool, or just create a new task
     *
     * @param useRecycleTask true, using recycle task if has one,
     * otherwise, always create new task
     */
    public Builder<U> useRecycleTask(boolean useRecycleTask) {
      this.useRecycleTask = useRecycleTask;
      return this;
    }

    /**
     * [NOTICED] must be called, otherwise the task won't start
     */
    public BaseThreadTask start() {
      this.runnable.setRunCallback(callback);
      this.runnable.setObserverOn(observerOn);
      return instance.startTask(this);
    }
  }
}
