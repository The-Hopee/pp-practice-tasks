package practice4;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** ДЗ4: сравнение четырёх исполнителей для ожидания и вычислений. */
public class WorkStealing {
    enum TaskDistribution { UNIFORM, PERIODIC, PARETO }
    enum Workload { SLEEP, CPU }

    interface Shutdownable { void shutdown(); }
    public interface ShutdownableExecutor extends Shutdownable, Executor { }

    // В этой учебной обёртке shutdown() не только закрывает приём, но и ждёт задачи.
    static class FixedThreadPoolExecutor implements ShutdownableExecutor {
        private final ExecutorService executorService;

        FixedThreadPoolExecutor(int threads) {
            executorService = Executors.newFixedThreadPool(threads);
        }

        @Override
        public void execute(Runnable command) {
            executorService.execute(command);
        }

        @Override
        public void shutdown() {
            executorService.shutdown();
            boolean interrupted = false;
            for (;;) {
                try {
                    if (executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            // Сохраняем сигнал прерывания, но не оставляем принятые задачи незавершёнными.
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void joinAll(List<Thread> threads) {
        boolean interrupted = false;
        for (Thread thread : threads) {
            for (;;) {
                try {
                    thread.join();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    static class ThreadPerTaskExecutor implements ShutdownableExecutor {
        private final List<Thread> threads = new ArrayList<>();
        private boolean shuttingDown;

        @Override
        public synchronized void execute(Runnable command) {
            Objects.requireNonNull(command);
            if (shuttingDown) throw new RejectedExecutionException("Executor is shutting down");
            Thread thread = new Thread(command, "task-" + threads.size());
            thread.start();
            threads.add(thread);
        }

        @Override
        public void shutdown() {
            List<Thread> snapshot;
            synchronized (this) {
                shuttingDown = true;
                snapshot = new ArrayList<>(threads);
            }
            joinAll(snapshot);
        }
    }

    static class RoundRobinExecutor implements ShutdownableExecutor {
        protected final List<BlockingDeque<Runnable>> tasks = new ArrayList<>();
        private final List<Thread> workers = new ArrayList<>();
        protected final AtomicInteger pending = new AtomicInteger();
        protected volatile boolean shuttingDown;
        private int nextQueue;

        RoundRobinExecutor(int threadCount) {
            if (threadCount <= 0) throw new IllegalArgumentException("Thread count must be positive");
            for (int i = 0; i < threadCount; i++) tasks.add(new LinkedBlockingDeque<>());
            for (int i = 0; i < threadCount; i++) workers.add(spawnThread(i));
            workers.forEach(Thread::start);
        }

        protected Runnable takeTask(int id) throws InterruptedException {
            return tasks.get(id).pollFirst(1, TimeUnit.MILLISECONDS);
        }

        private Thread spawnThread(int id) {
            return new Thread(() -> {
                for (;;) {
                    try {
                        Runnable task = takeTask(id);
                        if (task != null) {
                            try {
                                task.run();
                            } catch (RuntimeException e) {
                                // Ошибка одной задачи не уничтожает работника и его очередь.
                                System.err.println("Task failed: " + e);
                            } finally {
                                pending.decrementAndGet();
                            }
                        } else if (shuttingDown && pending.get() == 0) {
                            return;
                        }
                    } catch (InterruptedException e) {
                        // Для нормального завершения используем флаг, не прерывание работников.
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "queue-worker-" + id);
        }

        @Override
        public synchronized void execute(Runnable command) {
            Objects.requireNonNull(command);
            if (shuttingDown) throw new RejectedExecutionException("Executor is shutting down");
            pending.incrementAndGet();
            tasks.get(nextQueue).addLast(command);
            nextQueue = (nextQueue + 1) % tasks.size();
        }

        @Override
        public void shutdown() {
            synchronized (this) {
                shuttingDown = true;
            }
            // pending учитывает и ожидающие, и выполняемые задачи. Завершающие маркеры
            // не нужны: их можно случайно украсть и преждевременно остановить работника.
            joinAll(workers);
        }
    }

    static class WorkStealingExecutor extends RoundRobinExecutor {
        WorkStealingExecutor(int threads) { super(threads); }

        @Override
        protected Runnable takeTask(int id) throws InterruptedException {
            Runnable task = tasks.get(id).pollFirst();
            if (task != null) return task;
            for (int offset = 1; offset < tasks.size(); offset++) {
                // Свои задачи берём с начала, чужие - с конца очереди.
                task = tasks.get((id + offset) % tasks.size()).pollLast();
                if (task != null) return task;
            }
            return tasks.get(id).pollFirst(1, TimeUnit.MILLISECONDS);
        }
    }

    enum ExecutorKind {
        THREAD_PER_TASK, ROUND_ROBIN, WORK_STEALING, FIXED_THREAD_POOL;

        ShutdownableExecutor create(int threads) {
            return switch (this) {
                case THREAD_PER_TASK -> new ThreadPerTaskExecutor();
                case ROUND_ROBIN -> new RoundRobinExecutor(threads);
                case WORK_STEALING -> new WorkStealingExecutor(threads);
                case FIXED_THREAD_POOL -> new FixedThreadPoolExecutor(threads);
            };
        }
    }

    static final long RANDOM_SEED = 42;
    static final long ITERATIONS_PER_UNIT = 200_000;
    private static volatile long blackHoleSink;

    /* Точно 200000 * difficulty шагов, поэтому сложность O(difficulty).
       Результат уходит в volatile-поле: JIT не может удалить вычисление как ненужное.
       Запись одна на задачу, не на итерацию; переполнение long в самом смешивании
       намеренное. difficulty - условная сложность, НЕ миллисекунды CPU-времени. */
    public static void blackHole(long difficulty) {
        if (difficulty < 0) throw new IllegalArgumentException("Negative difficulty");
        long iterations = Math.multiplyExact(difficulty, ITERATIONS_PER_UNIT);
        long value = 0x1234ABCD5678EF01L;
        for (long i = 0; i < iterations; i++) {
            value ^= value >>> 13;
            value *= 0x5DEECE66DL;
            value += i;
        }
        blackHoleSink = value;
    }

    static long[] createTaskDurations(TaskDistribution distribution, int count, int threads, int mean) {
        Random random = new Random(RANDOM_SEED);
        long[] durations = new long[count];
        long total = 0;
        for (int i = 0; i < count; i++) {
            durations[i] = switch (distribution) {
                case UNIFORM -> random.nextInt(2 * mean + 1);
                // Тяжёлые задачи попадают в одну очередь RoundRobin.
                case PERIODIC -> i % threads == 0 ? (long) mean * threads : 0;
                case PARETO -> Math.min(Math.round((mean / 3.0)
                        / Math.pow(1.0 - random.nextDouble(), 1.0 / 1.5)), (long) mean * 100);
            };
            total += durations[i];
        }
        long target = (long) count * mean;
        if (total == 0) { // Возможен на совсем маленькой случайной выборке.
            Arrays.fill(durations, mean);
            return durations;
        }
        double scale = (double) target / total;
        double remainder = 0;
        long normalized = 0;
        for (int i = 0; i < count; i++) {
            double exact = durations[i] * scale + remainder;
            durations[i] = (long) exact;
            remainder = exact - durations[i];
            normalized += durations[i];
        }
        for (int i = 0; normalized < target; i = (i + 1) % count) {
            durations[i]++;
            normalized++;
        }
        for (int i = 0; normalized > target; i = (i + 1) % count) {
            if (durations[i] > 0) { durations[i]--; normalized--; }
        }
        return durations;
    }

    record Measurement(double submissionMs, double totalMs, int completed) { }

    static Measurement measureExecutor(ExecutorKind kind, Workload workload, long[] durations, int threads) {
        AtomicInteger completed = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // Подготовка задач вне замера. Счётчик и обработка ошибок одинаковы для всех.
        List<Runnable> commands = new ArrayList<>();
        for (long duration : durations) {
            commands.add(() -> {
                try {
                    if (workload == Workload.SLEEP) Thread.sleep(duration);
                    else blackHole(duration);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    completed.incrementAndGet();
                }
            });
        }
        // Конструктор включён в полное время: запуск работников тоже стоит времени.
        long start = System.nanoTime();
        ShutdownableExecutor executor = kind.create(threads);
        long submissionEnd;
        try {
            commands.forEach(executor::execute);
            submissionEnd = System.nanoTime();
        } finally {
            executor.shutdown();
        }
        long finish = System.nanoTime();
        if (failure.get() != null) throw new IllegalStateException("Task failed", failure.get());
        if (completed.get() != durations.length) throw new AssertionError("Lost tasks: " + kind);
        return new Measurement((submissionEnd - start) / 1_000_000.0,
                (finish - start) / 1_000_000.0, completed.get());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void selfTest() throws InterruptedException {
        for (ExecutorKind kind : ExecutorKind.values()) {
            ShutdownableExecutor executor = kind.create(3);
            AtomicInteger[] seen = new AtomicInteger[101];
            for (int i = 0; i < seen.length; i++) {
                seen[i] = new AtomicInteger();
                int id = i;
                executor.execute(() -> { blackHole(id % 2); seen[id].incrementAndGet(); });
            }
            executor.shutdown();
            for (AtomicInteger value : seen) require(value.get() == 1, "Not exactly once: " + kind);
            executor.shutdown(); // Повторное завершение разрешено.
            boolean rejected = false;
            try { executor.execute(() -> {}); }
            catch (RejectedExecutionException expected) { rejected = true; }
            require(rejected, "Accepted after shutdown: " + kind);
            kind.create(2).shutdown(); // Пустой исполнитель тоже должен завершиться.

            ShutdownableExecutor racing = kind.create(3);
            AtomicInteger accepted = new AtomicInteger(), finished = new AtomicInteger();
            Thread producer = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    try { racing.execute(finished::incrementAndGet); accepted.incrementAndGet(); }
                    catch (RejectedExecutionException expected) { break; }
                }
            });
            producer.start();
            racing.shutdown();
            producer.join();
            require(accepted.get() == finished.get(), "execute/shutdown race: " + kind);

            ShutdownableExecutor interrupted = kind.create(2);
            AtomicInteger done = new AtomicInteger();
            interrupted.execute(() -> { blackHole(2); done.incrementAndGet(); });
            Thread.currentThread().interrupt();
            interrupted.shutdown();
            require(Thread.interrupted(), "Interruption lost: " + kind);
            require(done.get() == 1, "Interrupted shutdown did not wait: " + kind);
        }
        for (TaskDistribution distribution : TaskDistribution.values()) {
            long[] durations = createTaskDurations(distribution, 240, 6, 4);
            require(Arrays.stream(durations).sum() == 960, "Different total work");
            require(Arrays.stream(durations).allMatch(x -> x >= 0), "Negative duration");
            require(Arrays.equals(durations, createTaskDurations(distribution, 240, 6, 4)),
                    "Seed is not reproducible");
        }
        blackHole(0);
        require(blackHoleSink == 0x1234ABCD5678EF01L, "Zero difficulty");
        boolean rejected = false;
        try { blackHole(-1); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "Negative difficulty accepted");
        System.out.println("Executor self-test: OK (exactly once, shutdown, rejection, race, interruption, distributions)");
    }

    private static double median(List<Measurement> values, boolean submission) {
        double[] times = values.stream().mapToDouble(x -> submission ? x.submissionMs() : x.totalMs()).sorted().toArray();
        int middle = times.length / 2;
        return times.length % 2 == 1 ? times[middle] : (times[middle - 1] + times[middle]) / 2;
    }

    /* RESULTS_BEGIN
       Замер 08.10.2026: Windows 11, Temurin 21.0.12.1, Intel i5-11400F,
       6 физических / 12 логических ядер. 240 задач, 6 работников в пулах,
       средняя сложность 4, сумма 960, seed=42, 1 прогрев + 3 замера.
       CPU: 200000 шагов на единицу. Медианы полного времени, миллисекунды:

       Нагрузка / распределение    ThreadPerTask  RoundRobin  WorkStealing  FixedPool
       SLEEP / UNIFORM                   27.898     235.460       206.310    196.179
       SLEEP / PERIODIC                  41.538     991.292       178.648    174.741
       SLEEP / PARETO                   288.391     428.434       412.321    398.057
       CPU   / UNIFORM                   36.893      74.598        63.672     54.553
       CPU   / PERIODIC                  46.688     324.747        64.681     56.391
       CPU   / PARETO                   114.519     133.078       133.137    120.448

       Полное время включает создание исполнителя, передачу и завершение задач.
       Все 72 измеряемых запуска завершили ровно 240 задач; данные всех повторов
       и время подачи (включая создание исполнителя) сохранены в benchmark.csv.

       1. PERIODIC специально кладёт тяжёлые задачи в одну очередь RoundRobin.
          Остальные работники простаивают: FixedPool быстрее примерно в 5.7 раза
          при sleep и 5.8 раза при CPU. Общая очередь и кража задач убирают этот перекос.
       2. В этой серии FixedPool быстрее самодельных пулов. У WorkStealing есть
          расходы на обход очередей и ожидание до 1 мс; кража не гарантирует победу.
       3. ThreadPerTask быстрее на этих 240 задачах. Для sleep ожидания перекрываются,
          а CPU может занять больше 6 работников и использовать 12 логических ядер.
          Число одновременно работающих потоков у него не равно числу потоков пула.
          Этот результат нельзя переносить на исходные 100000 отдельных потоков.
       4. В PARETO длинную отдельную задачу нельзя разделить кражей. Хвост нагрузки
          остаётся даже при хорошем распределении очередей.
       5. blackHole заменяет ожидание полезной CPU-нагрузкой; difficulty не задаёт
          миллисекунды. Нельзя сравнивать абсолютные времена SLEEP и CPU как
          выполнение одинаковой по времени работы. Сравниваем исполнители внутри режима.
       6. Это учебный замер, не JMH: всего три повтора, влияют JIT, планировщик,
          фоновые программы и разрешение таймеров Windows. Медиана уменьшает выбросы,
          но не делает результаты универсальными.
       RESULTS_END */

    public static void main(String[] args) throws Exception {
        int tasks = 240, threads = 6, mean = 4, warmups = 1, repeats = 3;
        Path csv = Path.of("benchmark.csv");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--tasks" -> tasks = Integer.parseInt(args[++i]);
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--mean" -> mean = Integer.parseInt(args[++i]);
                case "--warmups" -> warmups = Integer.parseInt(args[++i]);
                case "--repeats" -> repeats = Integer.parseInt(args[++i]);
                case "--csv" -> csv = Path.of(args[++i]);
                case "--check" -> { selfTest(); return; }
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        if (tasks <= 0 || threads <= 0 || mean <= 0 || mean > Integer.MAX_VALUE / 2 - 1
                || warmups < 1 || repeats < 1) throw new IllegalArgumentException("Invalid benchmark parameters");
        System.out.printf(Locale.ROOT, "Java %s; %s %s; %d logical CPUs%n",
                System.getProperty("java.version"), System.getProperty("os.name"),
                System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors());
        System.out.printf(Locale.ROOT, "tasks=%d, poolThreads=%d, mean=%d, seed=%d, warmups=%d, repeats=%d%n",
                tasks, threads, mean, RANDOM_SEED, warmups, repeats);
        System.out.println("SLEEP: milliseconds; CPU: difficulty units; steps/unit=" + ITERATIONS_PER_UNIT);
        System.out.println("Times include executor construction, submission and completion; output is excluded.");
        Files.createDirectories(csv.toAbsolutePath().getParent());
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(csv, StandardCharsets.UTF_8))) {
            writer.println("workload,distribution,executor,trial,submission_ms,total_ms,completed,tasks,threads,mean,warmups,repeats,seed,iterations_per_unit,java,os,logical_cpus");
            for (Workload workload : Workload.values()) {
                for (TaskDistribution distribution : TaskDistribution.values()) {
                    long[] durations = createTaskDurations(distribution, tasks, threads, mean);
                    System.out.printf("%n%s / %s; total difficulty=%d%n", workload, distribution,
                            Arrays.stream(durations).sum());
                    List<List<Measurement>> results = new ArrayList<>();
                    for (ExecutorKind ignored : ExecutorKind.values()) results.add(new ArrayList<>());
                    // Перемешивание порядка уменьшает влияние положения в серии и прогрева.
                    for (int trial = -warmups; trial < repeats; trial++) {
                        List<ExecutorKind> order = new ArrayList<>(List.of(ExecutorKind.values()));
                        Collections.shuffle(order, new Random(RANDOM_SEED + trial + 17L * distribution.ordinal()
                                + 101L * workload.ordinal()));
                        for (ExecutorKind kind : order) {
                            Measurement result = measureExecutor(kind, workload, durations, threads);
                            if (trial >= 0) {
                                results.get(kind.ordinal()).add(result);
                                writer.printf(Locale.ROOT,
                                        "%s,%s,%s,%d,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%s,%s,%d%n",
                                        workload, distribution, kind, trial + 1, result.submissionMs(), result.totalMs(),
                                        result.completed(), tasks, threads, mean, warmups, repeats, RANDOM_SEED,
                                        ITERATIONS_PER_UNIT, System.getProperty("java.version"),
                                        System.getProperty("os.name"), Runtime.getRuntime().availableProcessors());
                                writer.flush();
                            }
                        }
                    }
                    for (ExecutorKind kind : ExecutorKind.values()) {
                        List<Measurement> values = results.get(kind.ordinal());
                        System.out.printf(Locale.ROOT, "%-18s submit median=%8.3f ms; total median=%8.3f ms%n",
                                kind, median(values, true), median(values, false));
                    }
                }
            }
        }
        System.out.println("CSV: " + csv.toAbsolutePath());
        System.out.println("blackHole sink: " + blackHoleSink);
    }
}
