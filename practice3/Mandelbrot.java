package practice3;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/** ДЗ3: параллельный расчёт каждого пикселя множества Мандельброта. */
public class Mandelbrot {
    private static final double X_MIN = -2.5, X_MAX = 1.0;
    private static final double Y_MIN = -1.25, Y_MAX = 1.25;
    private static final int INSIDE = -1;

    private final int width, height, maxIterations;

    public Mandelbrot(int width, int height, int maxIterations) {
        if (width <= 0 || height <= 0 || maxIterations <= 0) {
            throw new IllegalArgumentException("Width, height and iteration limit must be positive");
        }
        this.width = width;
        this.height = height;
        this.maxIterations = maxIterations;
    }

    /* z(0) = 0, z(n+1) = z(n)^2 + c.
       Если |z| > 2, точка точно вне множества. Поэтому |z|^2 > 4, а не > 2.
       INSIDE означает, что точка не вышла за круг за maxIterations шагов.
       Это конечное приближение: для пограничных точек оно не доказывает принадлежность.
       Отдельный маркер -1 отличает его от выхода ровно на последнем шаге. */
    int countIterations(double cx, double cy) {
        double zx = 0, zy = 0;
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            double nextX = zx * zx - zy * zy + cx;
            zy = 2 * zx * zy + cy;
            zx = nextX;
            if (zx * zx + zy * zy > 4.0) {
                return iteration + 1;
            }
        }
        return INSIDE;
    }

    private void calculateRow(int[][] result, int y) {
        double cy = Y_MAX - (y + 0.5) * (Y_MAX - Y_MIN) / height;
        for (int x = 0; x < width; x++) {
            double cx = X_MIN + (x + 0.5) * (X_MAX - X_MIN) / width;
            result[y][x] = countIterations(cx, cy);
        }
    }

    int[][] calculateSequential() {
        int[][] result = new int[height][width];
        for (int y = 0; y < height; y++) {
            calculateRow(result, y);
        }
        return result;
    }

    int[][] calculateParallel(int threadCount) throws InterruptedException, ExecutionException {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("Thread count must be positive");
        }
        int workers = Math.min(threadCount, height);
        int[][] result = new int[height][width];
        AtomicInteger nextRow = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        Future<?>[] futures = new Future<?>[workers];
        try {
            for (int i = 0; i < workers; i++) {
                futures[i] = pool.submit(() -> {
                    int y;
                    while (!Thread.currentThread().isInterrupted()
                            && (y = nextRow.getAndIncrement()) < height) {
                        calculateRow(result, y);
                    }
                });
            }
            // get() ждёт всех работников и обеспечивает видимость записанных пикселей.
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        // Строку получает только один работник: блокировки для самих пикселей не нужны.
        return result;
    }

    private void saveImage(int[][] result, Path path) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int iterations = result[y][x];
                int color = iterations == INSIDE ? Color.BLACK.getRGB()
                        : Color.HSBtoRGB((float) (0.58 + 0.025 * iterations), 0.8f, 1.0f);
                image.setRGB(x, y, color);
            }
        }
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        if (!ImageIO.write(image, "png", path.toFile())) {
            throw new IOException("PNG encoder not available");
        }
    }

    private void printAscii(int[][] result) {
        String chars = " .:-=+*#%@";
        // Уменьшаем только вывод, расчёт всё равно выполнен для каждого пикселя.
        int stepX = Math.max(1, (width + 99) / 100);
        int stepY = Math.max(1, (height + 39) / 40);
        for (int y = 0; y < height; y += stepY) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < width; x += stepX) {
                int value = result[y][x];
                row.append(value == INSIDE ? '@'
                        : chars.charAt(Math.min(chars.length() - 1,
                        value * (chars.length() - 1) / maxIterations)));
            }
            System.out.println(row);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void selfTest() throws Exception {
        Mandelbrot points = new Mandelbrot(1, 1, 100);
        check(points.countIterations(0, 0) == INSIDE, "c=0");
        check(points.countIterations(-1, 0) == INSIDE, "c=-1");
        check(points.countIterations(-2, 0) == INSIDE, "c=-2: radius must be 2");
        check(points.countIterations(1, 0) == 3, "c=1 must escape at step 3");
        check(points.countIterations(2, 0) == 2, "c=2");
        check(new Mandelbrot(1, 1, 3).countIterations(1, 0) == 3,
                "Escape on the final iteration is outside");
        for (int[] size : new int[][]{{101, 43}, {7, 3}, {1, 1}}) {
            Mandelbrot fractal = new Mandelbrot(size[0], size[1], 200);
            int[][] expected = fractal.calculateSequential();
            for (int threads : new int[]{1, 2, 6, 8}) {
                check(Arrays.deepEquals(expected, fractal.calculateParallel(threads)),
                        "Parallel result differs; threads=" + threads);
            }
        }
        System.out.println("Mandelbrot self-test: OK (known points, last step, all pixels, small grids)");
    }

    public static void main(String[] args) throws Exception {
        int width = 1000, height = 720, maxIterations = 1000;
        int threads = Math.min(6, Runtime.getRuntime().availableProcessors());
        Path png = null;
        boolean ascii = false, verify = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--width" -> width = Integer.parseInt(args[++i]);
                case "--height" -> height = Integer.parseInt(args[++i]);
                case "--iterations" -> maxIterations = Integer.parseInt(args[++i]);
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--png" -> png = Path.of(args[++i]);
                case "--ascii" -> ascii = true;
                case "--verify" -> verify = true;
                case "--check" -> { selfTest(); return; }
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        Mandelbrot fractal = new Mandelbrot(width, height, maxIterations);
        // Короткий прогрев до замера, без вывода и записи изображения.
        new Mandelbrot(100, 72, maxIterations).calculateParallel(threads);
        long start = System.nanoTime();
        int[][] result = fractal.calculateParallel(threads);
        double elapsed = (System.nanoTime() - start) / 1_000_000.0;
        long inside = 0;
        for (int[] row : result) {
            for (int value : row) {
                if (value == INSIDE) inside++;
            }
        }
        System.out.printf(Locale.ROOT,
                "%dx%d, %d iterations, %d workers: %.3f ms%nInside approximation: %d / %d pixels%n",
                width, height, maxIterations, Math.min(threads, height), elapsed,
                inside, (long) width * height);
        if (verify) {
            start = System.nanoTime();
            int[][] reference = fractal.calculateSequential();
            double sequential = (System.nanoTime() - start) / 1_000_000.0;
            check(Arrays.deepEquals(reference, result), "Parallel and sequential pixels differ");
            System.out.printf(Locale.ROOT, "Sequential: %.3f ms; speedup: %.2f; every pixel matches%n",
                    sequential, sequential / elapsed);
        }
        if (png != null) {
            fractal.saveImage(result, png);
            System.out.println("PNG: " + png.toAbsolutePath());
        }
        if (ascii) fractal.printAscii(result);
    }
}
