package practice2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Philosophs {

    private static final class Fork {
        private final int id;
        private final Lock lock = new ReentrantLock(true);

        private Fork(int id) {
            this.id = id;
        }
    }

    private static final class Table {
        private final Fork[] forks;

        // Нужны только для проверки, что одна вилка
        // не используется двумя философами одновременно.
        private final AtomicIntegerArray forkUsers;
        private final AtomicBoolean conflictDetected = new AtomicBoolean(false);

        private Table(int philosopherCount) {
            forks = new Fork[philosopherCount];

            for (int i = 0; i < philosopherCount; i++) {
                forks[i] = new Fork(i);
            }

            forkUsers = new AtomicIntegerArray(philosopherCount);
        }

        public void eat(int philosopherId) {
            Fork left = forks[philosopherId];
            Fork right = forks[(philosopherId + 1) % forks.length];

            Fork first;
            Fork second;

            if (left.id < right.id) {
                first = left;
                second = right;
            } else {
                first = right;
                second = left;
            }

            first.lock.lock();
            try {
                second.lock.lock();
                try {
                    eatWithBothForks(philosopherId, left, right);
                } finally {
                    second.lock.unlock();
                }
            } finally {
                first.lock.unlock();
            }
        }

        private void eatWithBothForks(
                int philosopherId,
                Fork left,
                Fork right
        ) {
            int leftUsers = forkUsers.incrementAndGet(left.id);
            int rightUsers = forkUsers.incrementAndGet(right.id);

            if (leftUsers != 1 || rightUsers != 1) {
                conflictDetected.set(true);
            }

            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                forkUsers.decrementAndGet(right.id);
                forkUsers.decrementAndGet(left.id);
            }
        }

        public boolean isConflictDetected() {
            return conflictDetected.get();
        }
    }

    public static void main(String[] args) throws Exception {
        final int philosopherCount = 5;
        final int mealsPerPhilosopher = 100;

        Table table = new Table(philosopherCount);

        ExecutorService pool =
                Executors.newFixedThreadPool(philosopherCount);

        List<Future<Integer>> results = new ArrayList<>();

        try {
            for (int philosopher = 0;
                 philosopher < philosopherCount;
                 philosopher++) {

                final int philosopherId = philosopher;

                results.add(pool.submit(() -> {
                    int meals = 0;

                    while (meals < mealsPerPhilosopher) {
                        table.eat(philosopherId);
                        meals++;

                        Thread.yield();
                    }

                    return meals;
                }));
            }

            for (int philosopher = 0;
                 philosopher < philosopherCount;
                 philosopher++) {

                int meals = results
                        .get(philosopher)
                        .get(10, TimeUnit.SECONDS);

                if (meals != mealsPerPhilosopher) {
                    throw new AssertionError(
                            "Philosopher " + philosopher
                                    + " ate " + meals + " times"
                    );
                }
            }

        } catch (TimeoutException e) {
            throw new AssertionError(
                    "Possible deadlock: philosophers did not finish",
                    e
            );
        } finally {
            pool.shutdownNow();
        }

        if (table.isConflictDetected()) {
            throw new AssertionError(
                    "Two philosophers used the same fork"
            );
        }

        System.out.println(
                "OK: every philosopher ate "
                        + mealsPerPhilosopher
                        + " times, no deadlock detected"
        );
    }
}

/*
    1. Ресурсы блокируются по порядку, а освобождаются в обратном порядке.
     Можно ли в таком случае организовать взаимную блокировку?
    Ответ: Нет, нельзя, потому что чтобы получить дедлок, нужно заблокировать мьютексы в следующем порядке: 1 -> 2, а разблокировать 1 -> 2. Тогда мьютекс 2 будет ждать пока его разблокируют, но никогда этого не дождется
    2. Можно ли организовать взаимную блокировку, если отдавать ресурсы в том-же порядке, в каком мы их берём?
    Ответ: Да, в таком случае дедлок возникнет.
 */