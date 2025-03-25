package org.kpi;

import org.apache.commons.lang3.time.StopWatch;
import org.apache.commons.lang3.tuple.Pair;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.DefaultCategoryDataset;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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

public class Main {

    private static final StopWatch STOP_WATCH = new StopWatch();

    public static void main(String[] args) {
        int[] sizes = {10000, 15000, 20000, 250000};
        int[] ranges = {10000, 15000, 20000, 250000};

        for (int i = 0; i < sizes.length; i++) {
            runBenchmark(sizes[i], ranges[i]);
        }
    }

    public static void runBenchmark(int size, int range) {

        List<Integer> numbers = getListOfNumber(size, range);
        try {

            System.out.println("\nTesting array size: " + size);

            System.out.println("\nCheck correctness:");
            Pair<List<Integer>, Double> linearResult = measureExecutionTime(() -> checkModeAndMedian(numbers));
            long linearTime = STOP_WATCH.getTime(TimeUnit.MILLISECONDS);

            System.out.println("Modes : " + linearResult.getLeft());
            System.out.println("Median : " + linearResult.getRight());

            System.out.println("\nCheck parallelization:");
            Map<Integer, Long> results = new LinkedHashMap<>();

            for (CoreState state : CoreState.values()) {
                Pair<List<Integer>, Double> parallelResult = measureExecutionTime(() -> {
                    try {
                        return processParallel(numbers, state.getValue(), range);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                results.put(state.getValue(), STOP_WATCH.getTime(TimeUnit.MILLISECONDS));

                System.out.println(state.getValue() + " cores Mode : " + parallelResult.getLeft());
                System.out.println(state.getValue() + " cores Median : " + parallelResult.getRight());
            }

            showGraphics(results, linearTime, size);

        } catch (Exception e) {
            System.err.println("Error processing size " + size + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static <T> T measureExecutionTime(Supplier<T> task) {
        STOP_WATCH.reset();
        STOP_WATCH.start();
        T result = task.get();
        STOP_WATCH.stop();
        return result;
    }

    public static Pair<List<Integer>, Double> processParallel(List<Integer> numbers, int threadCount, int range) throws ExecutionException, InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        int segmentSize = numbers.size() / threadCount;

        List<Future<Map<Integer, Integer>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            int start = i * segmentSize;
            int end = i == threadCount - 1 ? numbers.size() : i * segmentSize + segmentSize;

            futures.add(executor.submit(() -> countFrequenciesInRange(numbers.subList(start, end))));
        }

        Map<Integer, Integer> frequencyMap = new HashMap<>(range);
        for (Future<Map<Integer, Integer>> future : futures) {
            Map<Integer, Integer> resultFrequency = future.get();
            resultFrequency.forEach((key, value) -> frequencyMap.merge(key, value, Integer::sum));
        }

        Double resultMedian = findMedian(frequencyMap, numbers.size(), numbers);
        List<Integer> resultMode = findMode(frequencyMap);

        executor.shutdown();
        return Pair.of(resultMode, resultMedian);
    }

    private static Map<Integer, Integer> countFrequenciesInRange(List<Integer> numbers) {
        Map<Integer, Integer> frequencyMap = new HashMap<>(numbers.size());
        for (Integer number : numbers) {
            frequencyMap.merge(number, 1, Integer::sum);
        }
        return frequencyMap;
    }

    public static List<Integer> findMode(Map<Integer, Integer> frequencyMap) {
        int maxFrequency = frequencyMap.values().stream()
                .max(Integer::compareTo)
                .orElse(0);

        return frequencyMap.entrySet().stream()
                .filter(entry -> entry.getValue() == maxFrequency)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    public static Double findMedian(Map<Integer, Integer> frequencyMap, int size, List<Integer> numbers) {
        List<Integer> list = new ArrayList<>();
        Map<Integer, Integer> sortedMap = new TreeMap<>(frequencyMap);
        for (Map.Entry<Integer, Integer> entry : sortedMap.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                list.add(entry.getKey());
            }
        }

        if (size % 2 == 1) {
            return Double.valueOf(list.get(size / 2));
        } else {
            return (Double.valueOf(list.get(size / 2)) + Double.valueOf(list.get((size / 2) - 1))) / 2.0;
        }
    }

    public static List<Integer> getListOfNumber(int size, int nextInt) {
        Random random = new Random();
        List<Integer> numbers = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            numbers.add(random.nextInt(nextInt));
        }
        return numbers;
    }

    public static Pair<List<Integer>, Double> checkModeAndMedian(List<Integer> numbers) {
        Collections.sort(numbers);

        Map<Integer, Integer> frequencyMap = new HashMap<>();
        int maxFreq = 0;
        List<Integer> modes = new ArrayList<>();

        for (int num : numbers) {
            int freq = frequencyMap.merge(num, 1, Integer::sum);
            if (freq > maxFreq) {
                maxFreq = freq;
                modes.clear();
                modes.add(num);
            } else if (freq == maxFreq) {
                modes.add(num);
            }
        }

        Double median = 0.0;
        if (numbers.size() % 2 == 1) {
            median = Double.valueOf(numbers.get(numbers.size() / 2));

        } else {
            median = ((Double.valueOf(numbers.get(numbers.size() / 2)) + Double.valueOf(numbers.get((numbers.size() / 2) - 1))) / 2.0);
        }

        return Pair.of(modes, median);
    }

    public static void showGraphics(Map<Integer, Long> resultTime, Long timeWithoutParallelism, Integer size) {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        resultTime.forEach((cores, time) -> dataset.addValue(time, "Time (ms)", cores));

        resultTime.keySet().forEach(cores -> dataset.addValue(timeWithoutParallelism, "One Thread Time (ms)", cores));

        JFreeChart chart = ChartFactory.createLineChart(
                "Performance Analysis, Size : " + size,
                "Number of Cores",
                "Elapsed Time (ms)",
                dataset,
                PlotOrientation.VERTICAL,
                true, true, false
        );

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Performance Chart");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(800, 600);
            frame.setLocationRelativeTo(null);

            ChartPanel chartPanel = new ChartPanel(chart);
            frame.setContentPane(chartPanel);
            frame.setVisible(true);
        });
    }
}