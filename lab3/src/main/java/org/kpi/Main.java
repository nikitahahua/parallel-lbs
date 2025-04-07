package org.kpi;

class Main {
    public static void main(String[] args) throws InterruptedException {
        ThreadPool threadPool = new ThreadPool();
        threadPool.start();
        Thread.sleep(220_000);
        threadPool.pause();
        threadPool.shutdown();
    }
}
