package practice5;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/*
 * ПП ДЗ5. Многопоточный анализ логов веб-сервера.
 *
 * Java 17+. Команды из папки practice5:
 *   java -jar LogAnalysisAssignment.jar check
 *   java -jar LogAnalysisAssignment.jar benchmark
 *   java -jar LogAnalysisAssignment.jar generate <каталог> 20 1000 mixed
 *   java -jar LogAnalysisAssignment.jar analyze <каталог> 4 2 per-url
 *   java -jar LogAnalysisAssignment.jar analyze <каталог> 4 2 global 50
 * Последний аргумент analyze - запрос остановки через указанное число миллисекунд.
 * Генерация файлов выполняется только по явной команде generate; check и benchmark
 * работают с временными каталогами и удаляют свои данные после завершения.
 *
 * Политика ошибок: повреждённую строку пропускаем, остальные строки читаем дальше.
 * Статусы принимаем от 200 до 599: 1xx - промежуточные ответы, не итог запроса.
 * Размер и длительность должны быть неотрицательными, путь начинается с '/'.
 * Ошибка открытия/чтения/закрытия файла записывается в failedFiles, уже учтённые
 * строки сохраняются. Другие файлы продолжают обрабатываться. В отчёте есть
 * отдельные счётчики плохих строк и файлов, а также первые пять примеров плохих строк.
 *
 * Частичный отчёт содержит все успешно добавленные записи уже начатых операций.
 * Началом операции считается чтение false из volatile-флага перед hasNext().
 * Если stop придёт после этой проверки, начатая операция может закончиться.
 * После наблюдения true работник не начинает следующую строку. Поток после acquire()
 * повторно проверяет stop, поэтому ожидавшие семафор задачи могут сразу завершиться.
 * completedFiles - только файлы, для которых достигнут EOF без ошибки;
 * finishedTasks - все завершившиеся задачи, в том числе пропущенные при остановке.
 *
 * Доказательство инвариантов (для запуска, вернувшего Report):
 * 1. На каждый элемент списка LogFile создаётся ровно одна задача, у неё отдельный
 *    итератор. next() возвращает строку один раз и не читает следующую заранее.
 *    В полном запуске все строки доступного файла учтены ровно один раз.
 *    Повтор одного пути во входном списке означает намеренную повторную обработку.
 * 2. Одно добавление под монитором одновременно меняет requests и ровно один
 *    из successful/errors. Поэтому requests = successful + errors для каждого URL.
 *    Суммы проверяются Math.addExact: переполнение не превращается в неверный отчёт.
 * 3. Длительность неотрицательна, maxDuration - максимум слагаемых, следовательно
 *    maxDuration <= totalDuration при requests > 0.
 * 4. activeFiles увеличивается только после acquire, уменьшается до release.
 *    Значит, 0 <= activeFiles <= semaphoreLimit. CAS сохраняет максимум наблюдений.
 *    finally закрывает итератор, уменьшает activeFiles и возвращает разрешение.
 *    После ожидания всех Future activeFiles = 0, finishedTasks = числу файлов.
 * 5. Каждая прочитанная строка либо добавлена в статистику, либо признана плохой:
 *    readLines = validEntries + malformedLines; сумма requests = validEntries.
 * 6. Порядок ресурсов: разрешение семафора -> файл -> монитор статистики одного URL
 *    (либо один глобальный монитор). acquire() никогда не вызывается под монитором.
 *    Под монитором нет I/O, ожидания Future и захвата другого монитора статистики.
 *    computeIfAbsent только создаёт ячейку, её монитор берётся после возврата метода.
 *    Задачи не ждут другие задачи пула; главный поток ждёт, не удерживая ресурсов.
 *    Цикла ожиданий нет; разрешение возвращается и при исключении. Предполагается,
 *    что файловые операции завершаются: зависание ОС/I/O - не цикл блокировок кода.
 * 7. IOException одного файла ловится внутри его задачи, а не вокруг всего запуска.
 *    volatile обеспечивает видимость stop между потоками, но не заменяет мониторы
 *    составных обновлений статистики. Atomic-счётчики не теряют конкурентные изменения.
 *
 * Итератор исходной заготовки читает следующую строку внутри next(): при ошибке
 * следующего чтения уже полученная строка могла потеряться. Здесь чтение ленивое,
 * а AutoCloseable позволяет закрыть файл и при досрочной остановке до EOF.
 */
public class LogAnalysisAssignment {
    public record LogEntry(String method, String path, int status,
                           long responseBytes, long durationMillis) { }
    public record LogFile(Path path) { }
    public record PathStatistics(long requests, long successful, long errors,
                                 long responseBytes, long totalDurationMillis,
                                 long maxDurationMillis) { }
    public record Counters(long finishedTasks, long startedFiles, long completedFiles,
                           long readLines, long validEntries, long malformedLines,
                           long readErrors, int activeFiles, int maxActiveFiles) { }
    public record FileFailure(Path path, String message) { }
    public record Report(Map<String, PathStatistics> paths, long totalRequests,
                         long successfulRequests, long errorRequests, long responseBytes,
                         long totalDurationMillis, long maxDurationMillis, Counters counters,
                         List<FileFailure> failedFiles, List<String> malformedExamples,
                         boolean stopped) {
        public Report {
            paths = Collections.unmodifiableMap(new TreeMap<>(paths));
            failedFiles = List.copyOf(failedFiles);
            malformedExamples = List.copyOf(malformedExamples);
        }
    }

    public enum Locking { GLOBAL, PER_URL }
    public enum PathPattern { MIXED, HOT, MANY }

    public static final class StopControl {
        private volatile boolean stopped;
        public void requestStop() { stopped = true; }
        public boolean isStopped() { return stopped; }
    }

    public static LogEntry parseLine(String line) {
        if (line == null || line.isBlank()) throw new IllegalArgumentException("Empty log line");
        String[] fields = line.strip().split("\\s+");
        if (fields.length != 5) throw new IllegalArgumentException("Expected 5 fields, got " + fields.length);
        if (!fields[0].matches("[A-Z]+") || !fields[1].startsWith("/")) {
            throw new IllegalArgumentException("Invalid HTTP method or request path");
        }
        try {
            int status = Integer.parseInt(fields[2]);
            long bytes = Long.parseLong(fields[3]);
            long duration = Long.parseLong(fields[4]);
            if (status < 200 || status > 599) throw new IllegalArgumentException("Final status must be 200..599");
            if (bytes < 0 || duration < 0) throw new IllegalArgumentException("Negative bytes or duration");
            return new LogEntry(fields[0], fields[1], status, bytes, duration);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid or overflowing numeric field", e);
        }
    }

    /** Один объект принадлежит одному работнику; закрывается в try-with-resources. */
    public static final class LogFileLines implements Iterator<String>, AutoCloseable {
        private final BufferedReader reader;
        private final Path path;
        private String nextLine;
        private boolean loaded, eof;

        private LogFileLines(Path path) throws IOException {
            this.path = path;
            reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        }

        public static LogFileLines iterator(Path path) throws IOException {
            return new LogFileLines(path);
        }

        @Override
        public boolean hasNext() {
            if (!loaded && !eof) {
                try {
                    nextLine = reader.readLine();
                    loaded = nextLine != null;
                    eof = nextLine == null;
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot read " + path, e);
                }
            }
            return loaded;
        }

        @Override
        public String next() {
            if (!hasNext()) throw new NoSuchElementException();
            String result = nextLine;
            nextLine = null;
            loaded = false;
            return result;
        }

        @Override
        public void close() throws IOException {
            eof = true;
            loaded = false;
            nextLine = null;
            reader.close();
        }
    }

    private static final class MutableStatistics {
        private long requests, successful, errors, bytes, duration, maxDuration;

        // Вызывается только под соответствующим монитором. Сначала проверяем суммы,
        // затем меняем поля: при переполнении ячейка не останется обновлённой наполовину.
        void add(LogEntry entry) {
            long nextRequests = Math.addExact(requests, 1);
            long nextBytes = Math.addExact(bytes, entry.responseBytes());
            long nextDuration = Math.addExact(duration, entry.durationMillis());
            requests = nextRequests;
            if (entry.status() < 400) successful++;
            else errors++;
            bytes = nextBytes;
            duration = nextDuration;
            maxDuration = Math.max(maxDuration, entry.durationMillis());
        }

        PathStatistics snapshot() {
            return new PathStatistics(requests, successful, errors, bytes, duration, maxDuration);
        }
    }

    private interface Accumulator {
        void add(LogEntry entry);
        Map<String, PathStatistics> snapshot();
    }

    private static final class GlobalAccumulator implements Accumulator {
        private final Map<String, MutableStatistics> data = new HashMap<>();

        @Override
        public synchronized void add(LogEntry entry) {
            data.computeIfAbsent(entry.path(), ignored -> new MutableStatistics()).add(entry);
        }

        @Override
        public synchronized Map<String, PathStatistics> snapshot() {
            Map<String, PathStatistics> result = new TreeMap<>();
            data.forEach((path, value) -> result.put(path, value.snapshot()));
            return result;
        }
    }

    private static final class PerUrlAccumulator implements Accumulator {
        private final ConcurrentHashMap<String, MutableStatistics> data = new ConcurrentHashMap<>();

        @Override
        public void add(LogEntry entry) {
            MutableStatistics cell = data.computeIfAbsent(entry.path(), ignored -> new MutableStatistics());
            synchronized (cell) { cell.add(entry); }
        }

        @Override
        public Map<String, PathStatistics> snapshot() {
            Map<String, PathStatistics> result = new TreeMap<>();
            data.forEach((path, cell) -> {
                synchronized (cell) { result.put(path, cell.snapshot()); }
            });
            return result;
        }
    }

    private static Accumulator accumulator(Locking locking) {
        return locking == Locking.GLOBAL ? new GlobalAccumulator() : new PerUrlAccumulator();
    }

    private static final class RunState {
        final Semaphore permits;
        final StopControl control;
        final Accumulator statistics;
        final AtomicLong finished = new AtomicLong(), started = new AtomicLong(), completed = new AtomicLong();
        final AtomicLong lines = new AtomicLong(), valid = new AtomicLong(), malformed = new AtomicLong();
        final AtomicLong readErrors = new AtomicLong();
        final AtomicInteger active = new AtomicInteger(), maxActive = new AtomicInteger();
        final ConcurrentLinkedQueue<FileFailure> failures = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<String> badExamples = new ConcurrentLinkedQueue<>();

        RunState(int limit, Accumulator statistics, StopControl control) {
            if (limit <= 0) throw new IllegalArgumentException("Semaphore limit must be positive");
            permits = new Semaphore(limit);
            this.statistics = statistics;
            this.control = control;
        }

        void observeActive(int current) {
            int old = maxActive.get();
            while (current > old && !maxActive.compareAndSet(old, current)) {
                old = maxActive.get();
            }
        }

        void process(LogFile file) {
            boolean acquired = false;
            try {
                if (control.isStopped()) return;
                permits.acquire();
                acquired = true;
                observeActive(active.incrementAndGet());
                if (control.isStopped()) return;
                started.incrementAndGet();
                boolean reachedEnd = false;
                try (LogFileLines iterator = LogFileLines.iterator(file.path())) {
                    long lineNumber = 0;
                    while (!control.isStopped()) {
                        if (!iterator.hasNext()) { reachedEnd = true; break; }
                        String line = iterator.next();
                        lineNumber++;
                        lines.incrementAndGet();
                        LogEntry entry;
                        try {
                            entry = parseLine(line);
                        } catch (IllegalArgumentException e) {
                            if (malformed.incrementAndGet() <= 5) {
                                badExamples.add(file.path() + ":" + lineNumber + ": " + e.getMessage());
                            }
                            continue;
                        }
                        statistics.add(entry);
                        valid.incrementAndGet();
                    }
                }
                if (reachedEnd) completed.incrementAndGet();
            } catch (IOException | UncheckedIOException e) {
                readErrors.incrementAndGet();
                failures.add(new FileFailure(file.path(), e.toString()));
            } catch (InterruptedException e) {
                control.requestStop();
                Thread.currentThread().interrupt();
            } finally {
                if (acquired) {
                    active.decrementAndGet();
                    permits.release();
                }
                finished.incrementAndGet();
            }
        }

        Report report() {
            Map<String, PathStatistics> paths = statistics.snapshot();
            long requests = 0, successful = 0, errors = 0, bytes = 0, duration = 0, maxDuration = 0;
            for (PathStatistics value : paths.values()) {
                requests = Math.addExact(requests, value.requests());
                successful = Math.addExact(successful, value.successful());
                errors = Math.addExact(errors, value.errors());
                bytes = Math.addExact(bytes, value.responseBytes());
                duration = Math.addExact(duration, value.totalDurationMillis());
                maxDuration = Math.max(maxDuration, value.maxDurationMillis());
            }
            Counters counters = new Counters(finished.get(), started.get(), completed.get(), lines.get(),
                    valid.get(), malformed.get(), readErrors.get(), active.get(), maxActive.get());
            List<FileFailure> failed = new ArrayList<>(failures);
            failed.sort(Comparator.comparing(x -> x.path().toString()));
            List<String> examples = new ArrayList<>(badExamples);
            Collections.sort(examples);
            return new Report(paths, requests, successful, errors, bytes, duration, maxDuration,
                    counters, failed, examples, control.isStopped());
        }
    }

    public static Report analyzeSequential(List<LogFile> files, StopControl control) {
        RunState state = new RunState(1, new GlobalAccumulator(), control);
        for (LogFile file : files) state.process(file);
        return state.report();
    }

    public static Report analyzeParallel(List<LogFile> files, int threads, int limit,
                                         Locking locking, StopControl control) {
        return analyzeParallel(files, threads, limit, accumulator(locking), control);
    }

    private static Report analyzeParallel(List<LogFile> files, int threads, int limit,
                                          Accumulator statistics, StopControl control) {
        if (threads <= 0) throw new IllegalArgumentException("Thread count must be positive");
        RunState state = new RunState(limit, statistics, control);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        boolean interrupted = false;
        Throwable failure = null;
        try {
            for (LogFile file : files) futures.add(pool.submit(() -> state.process(file)));
        } finally {
            pool.shutdown();
        }
        for (Future<?> future : futures) {
            for (;;) {
                try {
                    future.get();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                    control.requestStop();
                } catch (ExecutionException e) {
                    // Не скрываем ошибки программирования/переполнения. Всё равно ждём
                    // остальные задачи, чтобы не оставить открытые файлы и работников.
                    if (failure == null) failure = e.getCause();
                    control.requestStop();
                    break;
                }
            }
        }
        for (;;) {
            try {
                if (pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) break;
            } catch (InterruptedException e) {
                interrupted = true;
                control.requestStop();
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure != null) throw new IllegalStateException("Analysis task failed", failure);
        return state.report();
    }

    public static final class GenerateLogs {
        private static final String[] METHODS = {"GET", "POST", "PUT", "DELETE"};
        private static final String[] PATHS = {"/", "/index.html", "/login", "/api/users",
                "/api/orders", "/api/products", "/search", "/health"};
        private static final int[] STATUSES = {200, 200, 200, 201, 204, 301, 400, 401, 404, 500};

        private GenerateLogs() { }

        public static void generate(Path directory, int fileCount, int linesPerFile) throws IOException {
            generate(directory, fileCount, linesPerFile, PathPattern.MIXED);
        }

        public static void generate(Path directory, int fileCount, int linesPerFile,
                                    PathPattern pattern) throws IOException {
            if (fileCount < 0 || linesPerFile < 0) throw new IllegalArgumentException("Negative count");
            Files.createDirectories(directory);
            Random random = new Random(42);
            for (int fileId = 0; fileId < fileCount; fileId++) {
                Path file = directory.resolve("access-%03d.log".formatted(fileId));
                try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    for (int line = 0; line < linesPerFile; line++) {
                        String method = METHODS[random.nextInt(METHODS.length)];
                        int choice = random.nextInt(4096);
                        String path = switch (pattern) {
                            case MIXED -> PATHS[choice % PATHS.length];
                            case HOT -> choice < 4055 ? "/hot" : "/rare/" + choice;
                            case MANY -> "/item/" + choice;
                        };
                        int status = STATUSES[random.nextInt(STATUSES.length)];
                        long bytes = 100L + random.nextInt(100_000);
                        long duration = 1L + random.nextInt(500);
                        writer.write(method + " " + path + " " + status + " " + bytes + " " + duration);
                        writer.newLine();
                    }
                }
            }
        }
    }

    private static List<LogFile> listLogs(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".log"))
                    .sorted().map(LogFile::new).toList();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void checkInvariants(Report report, int fileCount, int limit) {
        Counters counts = report.counters();
        require(counts.finishedTasks() == fileCount, "Unfinished file tasks");
        require(counts.readLines() == counts.validEntries() + counts.malformedLines(), "Line accounting");
        require(report.totalRequests() == counts.validEntries(), "Request accounting");
        require(report.totalRequests() == report.successfulRequests() + report.errorRequests(), "Total statuses");
        require(counts.activeFiles() == 0, "Leaked active files");
        require(counts.maxActiveFiles() >= 0 && counts.maxActiveFiles() <= limit, "Semaphore limit exceeded");
        require(counts.readErrors() == report.failedFiles().size(), "File errors not reported");
        for (PathStatistics value : report.paths().values()) {
            require(value.requests() == value.successful() + value.errors(), "URL status accounting");
            require(value.maxDurationMillis() <= value.totalDurationMillis(), "Duration invariant");
        }
    }

    private static void checkEquivalent(Report expected, Report actual) {
        require(expected.paths().equals(actual.paths()), "Sequential and parallel URL statistics differ");
        require(expected.totalRequests() == actual.totalRequests()
                && expected.successfulRequests() == actual.successfulRequests()
                && expected.errorRequests() == actual.errorRequests()
                && expected.responseBytes() == actual.responseBytes()
                && expected.totalDurationMillis() == actual.totalDurationMillis()
                && expected.maxDurationMillis() == actual.maxDurationMillis(), "Totals differ");
        Counters left = expected.counters(), right = actual.counters();
        require(left.readLines() == right.readLines() && left.malformedLines() == right.malformedLines()
                && left.readErrors() == right.readErrors() && left.completedFiles() == right.completedFiles(),
                "File/line counters differ");
    }

    private static void checkCancellation(List<LogFile> files) throws Exception {
        StopControl control = new StopControl();
        Accumulator delegate = new PerUrlAccumulator();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Accumulator blocked = new Accumulator() {
            @Override
            public void add(LogEntry entry) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Cancellation test timeout");
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                delegate.add(entry);
            }
            @Override
            public Map<String, PathStatistics> snapshot() { return delegate.snapshot(); }
        };
        ExecutorService coordinator = Executors.newSingleThreadExecutor();
        try {
            Future<Report> future = coordinator.submit(() -> analyzeParallel(files, 4, 1, blocked, control));
            require(entered.await(10, TimeUnit.SECONDS), "No task entered accumulator");
            control.requestStop();
            release.countDown();
            Report partial = future.get(10, TimeUnit.SECONDS);
            checkInvariants(partial, files.size(), 1);
            require(partial.stopped() && partial.totalRequests() == 1 && partial.counters().readLines() == 1,
                    "A new line started after stop");
            require(partial.counters().completedFiles() == 0, "Stopped file marked complete");
        } finally {
            control.requestStop();
            release.countDown();
            coordinator.shutdownNow();
            require(coordinator.awaitTermination(10, TimeUnit.SECONDS), "Coordinator did not terminate");
        }
    }

    private static void selfCheck() throws Exception {
        Path directory = Files.createTempDirectory("pp5-check-");
        try {
            for (String invalid : new String[]{"", "GET / 200 1", "GET / 200 x 1", "GET / 200 -1 1",
                    "GET / 200 1 -1", "GET / 100 1 1", "GET / 600 1 1", "get / 200 1 1",
                    "GET relative 200 1 1", "GET / 200 9223372036854775808 1"}) {
                boolean rejected = false;
                try { parseLine(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
                require(rejected, "Accepted damaged line: " + invalid);
            }
            require(parseLine("  GET   /ok\t399 0 0  ").status() == 399, "Whitespace/status parsing");
            require(parseLine("GET /ok 400 0 0").status() == 400, "Error status boundary");

            Path generated = directory.resolve("generated");
            GenerateLogs.generate(generated, 3, 7);
            List<LogFile> generatedFiles = listLogs(generated);
            Report generatedReport = analyzeSequential(generatedFiles, new StopControl());
            require(generatedReport.totalRequests() == 21, "Generator/iterator lost records");
            checkInvariants(generatedReport, 3, 1);
            try (LogFileLines lines = LogFileLines.iterator(generatedFiles.get(0).path())) {
                for (int i = 0; i < 7; i++) {
                    require(lines.hasNext() && lines.hasNext(), "hasNext consumed a line");
                    parseLine(lines.next());
                }
                require(!lines.hasNext(), "EOF not detected");
                boolean rejected = false;
                try { lines.next(); } catch (NoSuchElementException expected) { rejected = true; }
                require(rejected, "Iterator next after EOF");
            }

            Path first = directory.resolve("first.log"), second = directory.resolve("second.log");
            Files.writeString(first, "GET /a 200 10 5\nPOST /a 404 7 3\nGET /b 301 0 0\n", StandardCharsets.UTF_8);
            Files.writeString(second, "broken\nGET /a 200 4 9\n", StandardCharsets.UTF_8);
            Path damaged = directory.resolve("bad-utf8.log");
            Files.write(damaged, new byte[]{(byte) 0xc3, (byte) 0x28});
            List<LogFile> files = List.of(new LogFile(first), new LogFile(second),
                    new LogFile(directory.resolve("missing.log")), new LogFile(damaged));
            Report reference = analyzeSequential(files, new StopControl());
            require(reference.paths().get("/a").equals(new PathStatistics(3, 2, 1, 21, 17, 9)), "Known URL totals");
            require(reference.totalRequests() == 4 && reference.counters().readLines() == 5
                    && reference.counters().malformedLines() == 1 && reference.counters().readErrors() == 2
                    && reference.counters().completedFiles() == 2, "Damaged file policy");
            for (Locking locking : Locking.values()) {
                for (int threads : new int[]{1, 4}) {
                    for (int limit : new int[]{1, 4}) {
                        Report actual = analyzeParallel(files, threads, limit, locking, new StopControl());
                        checkEquivalent(reference, actual);
                        checkInvariants(actual, files.size(), limit);
                    }
                }
            }
            // Ошибка чтения после нескольких буферов корректного текста:
            // уже возвращённые итератором строки должны сохраниться в частичном отчёте.
            Path brokenTail = directory.resolve("broken-tail.log");
            Files.writeString(brokenTail, "GET /prefix 200 1 1\n".repeat(4096), StandardCharsets.UTF_8);
            Files.write(brokenTail, new byte[]{(byte) 0xc3, (byte) 0x28}, StandardOpenOption.APPEND);
            Report prefix = analyzeParallel(List.of(new LogFile(brokenTail)), 4, 2,
                    Locking.PER_URL, new StopControl());
            checkInvariants(prefix, 1, 2);
            require(prefix.totalRequests() > 0 && prefix.counters().readErrors() == 1
                    && prefix.counters().completedFiles() == 0, "Read error discarded the valid prefix");
            // 28000 записей в одних и тех же URL: проверяем потерю обновлений под конкуренцией.
            Path stress = directory.resolve("stress");
            GenerateLogs.generate(stress, 7, 4000, PathPattern.HOT);
            List<LogFile> stressFiles = listLogs(stress);
            Report expected = analyzeSequential(stressFiles, new StopControl());
            for (Locking locking : Locking.values()) {
                Report actual = analyzeParallel(stressFiles, 8, 3, locking, new StopControl());
                checkEquivalent(expected, actual);
                checkInvariants(actual, 7, 3);
            }
            checkCancellation(files);
            // Прерывание главного ожидающего потока также останавливает анализ,
            // дожидается работников и возвращает частичный отчёт с сохранённым флагом.
            AtomicReference<Report> interruptedReport = new AtomicReference<>();
            AtomicReference<Throwable> interruptionFailure = new AtomicReference<>();
            Thread interruptedCaller = new Thread(() -> {
                try {
                    Thread.currentThread().interrupt();
                    interruptedReport.set(analyzeParallel(stressFiles, 4, 1,
                            Locking.PER_URL, new StopControl()));
                    require(Thread.currentThread().isInterrupted(), "Interrupt flag was lost");
                } catch (Throwable e) { interruptionFailure.set(e); }
            });
            interruptedCaller.start();
            interruptedCaller.join(10_000);
            require(!interruptedCaller.isAlive(), "Interrupted analysis did not terminate");
            if (interruptionFailure.get() != null) throw new AssertionError(interruptionFailure.get());
            checkInvariants(interruptedReport.get(), stressFiles.size(), 1);
            require(interruptedReport.get().stopped(), "Interrupted wait did not request stop");
            StopControl stopped = new StopControl();
            stopped.requestStop();
            Report empty = analyzeParallel(files, 4, 2, Locking.PER_URL, stopped);
            checkInvariants(empty, files.size(), 2);
            require(empty.totalRequests() == 0 && empty.counters().startedFiles() == 0, "Stopped run opened files");
            Report noFiles = analyzeParallel(List.of(), 4, 2, Locking.PER_URL, new StopControl());
            checkInvariants(noFiles, 0, 2);
            System.out.println("OK: parser, generator, iterator, both locks, exactly-once, counters, CAS bounds,");
            System.out.println("    missing/corrupted files, valid prefix, malformed line, cancellation, interruption,");
            System.out.println("    empty input; all checks passed.");
        } finally {
            deleteTemporaryData(directory);
        }
    }

    private record Configuration(int threads, int limit, Locking locking) { }

    /* BENCHMARK_RESULTS (25.10.2026)
     * Windows 11, Intel i5-11400F (6 ядер / 12 логических процессоров), Temurin 21.0.12.1.
     * Реальные файлы: 16 x 5000 = 80000 строк на набор, Random(42).
     * HOT: около 99% запросов в /hot, всего 42 URL; MANY: 4096 URL.
     * Один прогрев + три измерения каждой конфигурации; порядок перемешивается.
     * Время включает создание/завершение пула, чтение, парсинг, аккумулятор и Report.
     * Генерация и печать не входят в замер. Перед замерами файлы прочитаны эталоном:
     * это преимущественно работа с прогретым файловым кэшем, не тест скорости диска.
     *
     *                 HOT, медиана мс         MANY, медиана мс
     * Потоки Лимит    GLOBAL    PER_URL        GLOBAL    PER_URL     Пик activeFiles
     *      1     1    56.756     51.123        65.604     64.060       1
     *      1     4    53.497     52.308        64.738     65.670       1
     *      4     1    52.122     52.538        64.995     67.406       1
     *      4     4    19.958     24.866        27.435     22.699       4
     *
     * Все 48 измеренных запусков (2 набора x 8 конфигураций x 3 повтора)
     * дали ровно 80000 корректных записей и совпали с последовательным эталоном
     * по каждому полю каждого URL и по итоговым значениям. Разрешения не утекли.
     *
     * Выводы:
     * - При лимите 1 файл целиком удерживает разрешение, включая обновления статистики.
     *   Поэтому 4 работника не дают устойчивого ускорения: остальные ждут семафор.
     * - При 4 разрешениях переход с 1 на 4 потока дал ускорение HOT GLOBAL в 2.68 раза
     *   (53.497/19.958), MANY PER_URL в 2.89 раза (65.670/22.699).
     * - Для HOT блокировки по URL не устраняют основную конкуренцию: почти все записи
     *   всё равно захватывают монитор /hot. GLOBAL здесь быстрее (19.958 против 24.866).
     * - Для MANY отдельные мониторы позволяют обновлять разные URL параллельно:
     *   PER_URL быстрее GLOBAL примерно в 1.21 раза (27.435/22.699) при 4/4.
     * - Число разрешений выше числа работников не создаёт дополнительную параллельность.
     *   Пиковое activeFiles было 1 для одного работника и 4 только при конфигурации 4/4.
     * - Это небольшие учебные замеры, не JMH. Парсинг, дисковый кэш, JIT и планировщик
     *   влияют на результат; три повтора не позволяют объявить одну схему лучшей всегда.
     *
     * Эксперимент с ошибками (check): два нормальных файла, один отсутствующий,
     * один с неверным UTF-8; в нормальном файле дополнительно одна строка "broken".
     * Итог: finishedTasks=4, completedFiles=2, readLines=5, validEntries=4,
     * malformedLines=1, readErrors=2, activeFiles=0. Для /a: requests=3,
     * successful=2, errors=1, bytes=21, totalDuration=17, maxDuration=9.
     * /b: один успешный запрос с нулевыми размером и длительностью.
     * Все восемь параллельных конфигураций дали тот же результат, что и эталон.
     *
     * Отдельно проверены повреждение после корректного префикса, конкуренция
     * за один URL (28000 записей, 8 потоков, лимит 3), пустой вход, остановка до
     * начала и остановка во время add с ожидающими семафор работниками. Уже начатая
     * запись сохранилась, следующая не началась, все Future и работники завершились.
     * Проверено сохранение флага прерывания главного ожидающего потока.
     */
    private static void benchmark() throws IOException {
        Path directory = Files.createTempDirectory("pp5-benchmark-");
        try {
            List<Configuration> configurations = new ArrayList<>();
            for (int threads : new int[]{1, 4}) {
                for (int limit : new int[]{1, 4}) {
                    for (Locking locking : Locking.values()) {
                        configurations.add(new Configuration(threads, limit, locking));
                    }
                }
            }
            System.out.printf("Java %s; %s; logical CPUs=%d%n", System.getProperty("java.version"),
                    System.getProperty("os.name"), Runtime.getRuntime().availableProcessors());
            System.out.println("16 files x 5000 lines; seed=42; 1 warmup + 3 measured runs per configuration.");
            System.out.println("Includes open/read/parse/statistics/report and executor lifecycle; excludes generation/output.");
            for (PathPattern pattern : new PathPattern[]{PathPattern.HOT, PathPattern.MANY}) {
                Path input = directory.resolve(pattern.name());
                GenerateLogs.generate(input, 16, 5000, pattern);
                List<LogFile> files = listLogs(input);
                Report reference = analyzeSequential(files, new StopControl());
                Map<Configuration, List<Double>> times = new HashMap<>();
                Map<Configuration, Integer> peaks = new HashMap<>();
                configurations.forEach(config -> times.put(config, new ArrayList<>()));
                for (int trial = -1; trial < 3; trial++) {
                    List<Configuration> order = new ArrayList<>(configurations);
                    Collections.shuffle(order, new Random(42L + trial + 17L * pattern.ordinal()));
                    for (Configuration config : order) {
                        long start = System.nanoTime();
                        Report report = analyzeParallel(files, config.threads(), config.limit(), config.locking(), new StopControl());
                        double millis = (System.nanoTime() - start) / 1_000_000.0;
                        checkEquivalent(reference, report);
                        checkInvariants(report, files.size(), config.limit());
                        if (trial >= 0) {
                            times.get(config).add(millis);
                            peaks.merge(config, report.counters().maxActiveFiles(), Math::max);
                        }
                    }
                }
                System.out.println("\n" + pattern + ": records=" + reference.totalRequests() + ", URLs=" + reference.paths().size());
                for (Configuration config : configurations) {
                    List<Double> values = times.get(config);
                    Collections.sort(values);
                    System.out.printf(Locale.ROOT, "threads=%d permits=%d %-7s median=%8.3f ms range=[%.3f, %.3f] peak=%d%n",
                            config.threads(), config.limit(), config.locking(), values.get(1), values.get(0), values.get(2), peaks.get(config));
                }
            }
        } finally {
            deleteTemporaryData(directory);
        }
    }

    // Удаляются только данные в каталоге, который только что создал этот запуск
    // через createTempDirectory; пользовательский каталог команды generate не трогаем.
    private static void deleteTemporaryData(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static void printReport(Report report) {
        System.out.println("PATH requests success errors bytes total_ms max_ms");
        report.paths().forEach((path, value) -> System.out.printf("%s %d %d %d %d %d %d%n", path,
                value.requests(), value.successful(), value.errors(), value.responseBytes(),
                value.totalDurationMillis(), value.maxDurationMillis()));
        System.out.printf("TOTAL requests=%d success=%d errors=%d bytes=%d duration=%d max=%d stopped=%s%n",
                report.totalRequests(), report.successfulRequests(), report.errorRequests(), report.responseBytes(),
                report.totalDurationMillis(), report.maxDurationMillis(), report.stopped());
        System.out.println(report.counters());
        report.failedFiles().forEach(failure -> System.out.println("FILE ERROR " + failure.path() + ": " + failure.message()));
        report.malformedExamples().forEach(example -> System.out.println("BAD LINE " + example));
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Commands: check | benchmark | generate <directory> [files] [lines] [mixed|hot|many]");
            System.out.println("          analyze <directory> [threads] [permits] [global|per-url] [stopAfterMs]");
            return;
        }
        switch (args[0]) {
            case "check" -> selfCheck();
            case "benchmark" -> benchmark();
            case "generate" -> {
                if (args.length < 2) throw new IllegalArgumentException("Specify output directory");
                Path directory = Path.of(args[1]);
                int files = args.length > 2 ? Integer.parseInt(args[2]) : 20;
                int lines = args.length > 3 ? Integer.parseInt(args[3]) : 1000;
                PathPattern pattern = args.length > 4 ? PathPattern.valueOf(args[4].toUpperCase(Locale.ROOT)) : PathPattern.MIXED;
                GenerateLogs.generate(directory, files, lines, pattern);
                System.out.println("Generated " + files + " files in " + directory.toAbsolutePath());
            }
            case "analyze" -> {
                if (args.length < 2) throw new IllegalArgumentException("Specify log directory");
                List<LogFile> files = listLogs(Path.of(args[1]));
                int threads = args.length > 2 ? Integer.parseInt(args[2]) : 4;
                int permits = args.length > 3 ? Integer.parseInt(args[3]) : 4;
                Locking locking = args.length > 4 ? switch (args[4]) {
                    case "global" -> Locking.GLOBAL;
                    case "per-url" -> Locking.PER_URL;
                    default -> throw new IllegalArgumentException("Locking must be global or per-url");
                } : Locking.PER_URL;
                long stopAfter = args.length > 5 ? Long.parseLong(args[5]) : 0;
                if (stopAfter < 0) throw new IllegalArgumentException("Negative stop delay");
                StopControl control = new StopControl();
                Thread timer = new Thread(() -> {
                    try {
                        Thread.sleep(stopAfter);
                        control.requestStop();
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }, "analysis-stop-timer");
                if (stopAfter > 0) timer.start();
                try {
                    printReport(analyzeParallel(files, threads, permits, locking, control));
                } finally {
                    if (stopAfter > 0) {
                        timer.interrupt();
                        boolean interrupted = Thread.interrupted();
                        for (;;) {
                            try { timer.join(); break; }
                            catch (InterruptedException e) { interrupted = true; }
                        }
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }
}
