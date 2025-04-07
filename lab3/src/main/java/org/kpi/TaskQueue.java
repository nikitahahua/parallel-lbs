package org.kpi;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

class TaskQueue {

    private Queue<Task> innerQueue = new LinkedList<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public void offer(Task task) {
        lock.writeLock().lock();
        try {
            innerQueue.add(task);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Task take() {
        lock.writeLock().lock();
        try {
            return innerQueue.poll();

        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return innerQueue.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isEmpty() {
        lock.readLock().lock();
        try {
            return innerQueue.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            innerQueue.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }
}