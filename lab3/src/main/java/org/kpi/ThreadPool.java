package org.kpi;

import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class ThreadPool {

    private static final int BUFFER_INTERVAL = 45_000;
    private static final int WORKERS_AMOUNT = 4;
    private static final int SUBMITTERS_AMOUNT = 2;
    private static volatile boolean isBuffering = false;

    private volatile boolean isRunning = true;
    private volatile boolean isShutdown = false;

    private final TaskQueue taskQueue = new TaskQueue();
    private final Worker[] workers = new Worker[WORKERS_AMOUNT];
    private final Submitter[] submitters = new Submitter[SUBMITTERS_AMOUNT];
    private final ReentrantLock pauseLock = new ReentrantLock();
    private final Condition pausedCondition = pauseLock.newCondition();
    private final ReentrantLock bufferLock = new ReentrantLock();
    private final Condition bufferCondition = bufferLock.newCondition();
    private final ReentrantLock processLock = new ReentrantLock();
    private final Condition processingCondition = processLock.newCondition();

    private final Thread bufferThread;

    private final Object printLock = new Object();

    private List<Integer> totalWaitingTasks = new ArrayList<>();
    private List<Integer> totalQueueSizes = new ArrayList<>();
    private Pair<Integer, Long> totalWaitTime = Pair.of(0, 0L);

    public ThreadPool() {
        for (int i = 0; i < WORKERS_AMOUNT; i++) {
            workers[i] = new Worker();
        }
        for (int i = 0; i < SUBMITTERS_AMOUNT; i++) {
            submitters[i] = new Submitter();
        }
        bufferThread = new Thread(this::bufferProcessing);
        bufferThread.start();
    }

    public void start() {
        isRunning = true;
        for (Worker worker : workers) {
            worker.start();
        }

        for (Submitter submitter : submitters) {
            submitter.start();
        }
    }

    public void submit(Task task) {
        synchronized (printLock) {
            if (!isBuffering) {
                System.out.println(Thread.currentThread().getName() + " skip submitting task");
                return;
            }

            taskQueue.offer(task);
            System.out.println(Thread.currentThread().getName() + " submitted task" + task + " queue size: " + taskQueue.size());
        }
    }

    public void shutdown() {
        if (isBuffering) {
            totalQueueSizes.add(taskQueue.size());
        }

        try {
            for (Worker worker : workers) {
                worker.interrupt();
            }

            for (Submitter submitter : submitters) {
                submitter.interrupt();
            }
        } catch (Exception e) {
            System.err.println(Thread.currentThread().getName() + " interrupted while shutting down");
        }

        bufferThread.interrupt();
        isBuffering = false;
        isRunning = false;
        isShutdown = true;
        taskQueue.clear();
        System.out.println("Thread pool shut down");
        System.out.println("Avg Task Processing Time : " + getAvgTaskProcessingTime());
        System.out.println("Avg Queue Size : " + getAvgQueueSize());
        totalWaitingTasks.clear();
        totalQueueSizes.clear();
    }

    public void shutdownGracefully() {
        if (isBuffering) {
            totalQueueSizes.add(taskQueue.size());
        }

        isShutdown = true;
        isRunning = false;
        isBuffering = false;

        System.out.println("Avg Task Processing Time : " + getAvgTaskProcessingTime());
        System.out.println("Avg Queue Size : " + getAvgQueueSize());
        totalWaitingTasks.clear();
        totalQueueSizes.clear();
    }

    public void pause() {
        pauseLock.lock();
        isRunning = false;
        pauseLock.unlock();
        System.out.println("Thread pool paused");
    }

    public void resume() {
        pauseLock.lock();
        isRunning = true;
        pausedCondition.signalAll();
        pauseLock.unlock();
        System.out.println("Thread pool resumed");
    }

    public boolean isRunning() {
        return isRunning;
    }

    private void bufferProcessing() {
        while (!isShutdown) {
            try {
                isBuffering = true;
                System.out.println("\n" + Thread.currentThread() + "Buffering for " + BUFFER_INTERVAL + " seconds");

                Thread.sleep(BUFFER_INTERVAL);
                bufferLock.lock();
                try {
                    totalQueueSizes.add(taskQueue.size());
                    isBuffering = false;
                    processLock.lock();
                    processingCondition.signalAll();
                    processLock.unlock();
                    System.out.println(Thread.currentThread() + " STOP Buffering for " + BUFFER_INTERVAL + " seconds\n");
                    bufferCondition.signalAll();
                } finally {
                    bufferLock.unlock();
                }



                Thread.sleep(BUFFER_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println(Thread.currentThread().getName() + " buffer thread interrupted");
                return;
            }
        }

    }

    class Worker extends Thread {

        @Override
        public void run() {
            while (!isShutdown) {
                pauseLock.lock();
                try {
                    while (!isRunning) {
                        try {
                            pausedCondition.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            System.out.println(Thread.currentThread().getName() + " worker interrupted");
                            return;
                        }
                    }
                } finally {
                    pauseLock.unlock();
                }

                Task task = null;
                int size = 0;

                bufferLock.lock();
                try {
                    while (isBuffering) {
                        try {
                            bufferCondition.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            System.out.println(Thread.currentThread().getName() + " worker interrupted");
                            return;
                        }
                    }
                } finally {
                    bufferLock.unlock();
                }

                processLock.lock();
                task = taskQueue.take();
                size = taskQueue.size();
                System.out.println(Thread.currentThread().getName() + " trying to get task " + task);
                if (task == null) {
                    try {
                        System.out.println(Thread.currentThread().getName() + " waiting for new task as queue is empty ");
                        processingCondition.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.out.println(Thread.currentThread().getName() + " worker interrupted");
                        return;
                    }
                }
                processLock.unlock();

                if (task != null) {
                    synchronized (printLock) {
                        System.out.println(Thread.currentThread().getName() + " processing task " + task + " queue size " + size);
                    }
                    task.execute();
                    totalWaitingTasks.add(task.getSleepTime());
                }
            }
        }
    }

    class Submitter extends Thread {
        private static final int MIN_SLEEP = 5000;
        private static final int MAX_SLEEP = 15000;

        @Override
        public void run() {
            while (isRunning()) {
                try {
                    processLock.lock();
                    submit(new Task());
                    if (!taskQueue.isEmpty() && !isBuffering) {
                        processingCondition.signal();
                    }
                } finally {
                    processLock.unlock();
                }

                try {
                    Thread.sleep(MIN_SLEEP + (int) (Math.random() * (MAX_SLEEP - MIN_SLEEP)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println(Thread.currentThread().getName() + " submitter interrupted");
                    return;
                }
            }
        }
    }

    public Double getAvgTaskProcessingTime() {
        return (double) totalWaitingTasks.stream().reduce(0, Integer::sum) / totalWaitingTasks.size();
    }

    public Double getAvgQueueSize() {
        return (double) totalQueueSizes.stream().reduce(0, Integer::sum) / totalQueueSizes.size();
    }
}


class Task {
    private static final int MIN_SLEEP = 6000;
    private static final int MAX_SLEEP = 12000;

    private int sleepTime;

    public Task() {
        sleepTime = MIN_SLEEP + (int) (Math.random() * (MAX_SLEEP - MIN_SLEEP));
    }

    public void execute() {
        try {
            System.out.println(Thread.currentThread().getName() + " is waiting for " + sleepTime + " milliseconds");
            Thread.currentThread().sleep(sleepTime);
            System.out.println(Thread.currentThread().getName() + " executed task :  " + this);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public int getSleepTime() {
        return sleepTime;
    }

    @Override
    public String toString() {
        return "Task{" +
                "sleepTime=" + sleepTime +
                '}';
    }
}

