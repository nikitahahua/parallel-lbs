package org.kpi;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.StopWatch;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.DefaultCategoryDataset;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

enum CoreState {

    HALF(7),
    TWO(2 * 14),
    FOUR(4 * 14),
    EIGHT(8 * 14),
    SIXTEENTH(16 * 14);

    private Integer value;

    public Integer getValue() {
        return value;
    }

    CoreState(Integer value) {
        this.value = value;
    }
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class Result {

    private int oddCount;
    private int maxOdd;
    private long oddTime;

    @Override
    public String toString() {
        return "oddCount=" + oddCount +
                ", maxOdd=" + maxOdd;
    }

    public synchronized void update(int localOddCount, int localMaxOdd) {
        this.oddCount += localOddCount;
        this.maxOdd = Math.max(this.maxOdd, localMaxOdd);
    }

    public Result(int oddCount, int maxOdd) {
        this.oddCount = oddCount;
        this.maxOdd = maxOdd;
    }

    public static Result countOddStats(List<Integer> numbers) {
        return numbers.stream()
                .filter(num -> num % 2 != 0)
                .reduce(new Result(0, Integer.MIN_VALUE, 0),
                        (acc, num) -> new Result(acc.oddCount + 1, Math.max(acc.maxOdd, num), 0),
                        (r1, r2) -> new Result(r1.oddCount + r2.oddCount, Math.max(r1.maxOdd, r2.maxOdd), 0));
    }
}

public class Main {

    private final static Integer THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 2;
    private static final StopWatch STOP_WATCH = new StopWatch();

    public static void main(String[] args) {
        int[] sizes = {10000, 200000, 1000000, 5000000, 250000000};
        int ranges = 800000;
        for (int i = 0; i < sizes.length; i++) {
            System.out.println("List size : " + sizes[i]);
            List<Integer> listToTest = getListOfNumber(sizes[i], ranges);
            List<Long> results = new ArrayList<>();
            Result result = measureExecutionTime(() -> runBenchmark(listToTest));
            results.add(result.getOddTime());
            System.out.println("Not Synchronized, Time in MILLISECONDS: " + result.getOddTime());
            result = measureExecutionTime(() -> runSynchronizedBenchmark(listToTest));
            results.add(result.getOddTime());
            System.out.println("Synchronized, Time in MILLISECONDS: " + result.getOddTime());
            result = measureExecutionTime(() -> runAtomicBenchmark(listToTest));
            results.add(result.getOddTime());
            System.out.println("Atomic, Time in MILLISECONDS: " + result.getOddTime() + "\n");
            System.out.println("====================");

            showGraphics(results, sizes[i]);
        }
    }

    private static Result measureExecutionTime(Supplier<Result> task) {
        if (STOP_WATCH.isStarted()) {
            STOP_WATCH.stop();
        }
        STOP_WATCH.reset();
        STOP_WATCH.start();

        Result result = task.get();
        if (STOP_WATCH.isStarted()) {
            STOP_WATCH.stop();
        }

        long resultTime = STOP_WATCH.getTime(TimeUnit.MILLISECONDS);
        result.setOddTime(resultTime);
        return result;
    }


    public static Result runBenchmark(List<Integer> listToTest) {
        return measureExecutionTime(() -> Result.countOddStats(listToTest));
    }

    public static Result runSynchronizedBenchmark(List<Integer> listToTest) {
        return measureExecutionTime(() -> processSynchronizedParallel(listToTest));
    }

    public static Result runAtomicBenchmark(List<Integer> listToTest) {
        return measureExecutionTime(() -> processAtomicParallel(listToTest));
    }

    public static Result processSynchronizedParallel(List<Integer> numbers) {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        int segmentSize = numbers.size() / THREAD_COUNT;

        Result result = new Result();

        for (int i = 0; i < THREAD_COUNT; i++) {
            int start = i * segmentSize;
            int end = i == THREAD_COUNT - 1 ? numbers.size() : i * segmentSize + segmentSize;

            executor.submit(() -> {
                Result localResult = Result.countOddStats(numbers.subList(start, end));
                result.update(localResult.getOddCount(), localResult.getMaxOdd());
            });
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        return result;
    }

    public static Result processAtomicParallel(List<Integer> numbers) {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        int segmentSize = numbers.size() / THREAD_COUNT;

        AtomicInteger oddCount = new AtomicInteger(0);
        AtomicInteger maxOdd = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            int start = i * segmentSize;
            int end = i == THREAD_COUNT - 1 ? numbers.size() : i * segmentSize + segmentSize;

            executor.submit(() -> {
                Result localResult = Result.countOddStats(numbers.subList(start, end));

                int tempCountOddValue;
                do {
                    tempCountOddValue = oddCount.get();
                } while (!oddCount.compareAndSet(tempCountOddValue, oddCount.get() + localResult.getOddCount()));

                int tempMaxOddValue;
                do {
                    tempMaxOddValue = maxOdd.get();
                } while (!maxOdd.compareAndSet(tempMaxOddValue, Math.max(maxOdd.get(), localResult.getMaxOdd())));
            });
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        return new Result(oddCount.get(), maxOdd.get());
    }

    public static List<Integer> getListOfNumber(int size, int nextInt) {
        Random random = new Random();
        List<Integer> numbers = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            numbers.add(random.nextInt(nextInt));
        }
        return numbers;
    }

    public static void showGraphics(List<Long> resultTime, Integer size) {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        dataset.addValue(resultTime.get(0), "Time (ms)", "Simple Run");
        dataset.addValue(resultTime.get(1), "Time (ms)", "Sync Parallel Run");
        dataset.addValue(resultTime.get(2), "Time (ms)", "Atomic Parallel Run");

        JFreeChart chart = ChartFactory.createLineChart(
                "Performance Analysis, Size: " + size + ", Cores: " + THREAD_COUNT,
                "Measurement",
                "Elapsed Time (ms)",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false
        );


        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Performance Chart");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setSize(800, 600);
            frame.setLocationRelativeTo(null);

            ChartPanel chartPanel = new ChartPanel(chart);
            frame.setContentPane(chartPanel);
            frame.setVisible(true);
        });
    }
}