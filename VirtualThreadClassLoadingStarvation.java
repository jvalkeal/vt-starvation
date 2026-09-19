import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

public final class VirtualThreadClassLoadingStarvation {

	private static final Object ARCHIVE_MONITOR = new Object();
	private static final ReentrantLock FILE_LOCK = new ReentrantLock();
	private static final CountDownLatch FILE_LOCK_HELD = new CountDownLatch(1);
	private static final CountDownLatch RELEASE_FILE_LOCK = new CountDownLatch(1);

	public static void main(String[] args) throws Exception {
		System.out.printf("PID=%d, Java=%s, Processors=%d%n", ProcessHandle.current().pid(), Runtime.version(),
				Runtime.getRuntime().availableProcessors());

		Thread fileReader = Thread.ofPlatform().name("file-reader").start(() -> {
			FILE_LOCK.lock();
			try {
				System.out.println("file-reader acquired the FILE_LOCK");
				FILE_LOCK_HELD.countDown();
				System.out.println("file-reader waiting the RELEASE_FILE_LOCK");
				awaitLatch(RELEASE_FILE_LOCK);
			}
			finally {
				FILE_LOCK.unlock();
			}
		});

		FILE_LOCK_HELD.await();

		Thread monitorOwner = Thread.ofVirtual().name("vt-monitor-owner").start(() -> {
			synchronized (ARCHIVE_MONITOR) {
				FILE_LOCK.lock();
				try {
					System.out.println("vt-monitor acquired the FILE_LOCK");
				}
				finally {
					FILE_LOCK.unlock();
				}
			}
		});

		awaitState(monitorOwner, Thread.State.WAITING, true);
		System.out.println("vt-monitor-owner holds the ARCHIVE_MONITOR and is unmounted waiting for the FILE_LOCK");

		BlockingClassLoader loader = new BlockingClassLoader();
		Thread loaderOne = startClassLoad("vt-class-loader-1", loader, TargetOne.class.getName());
		Thread loaderTwo = startClassLoad("vt-class-loader-2", loader, TargetTwo.class.getName());
		awaitState(loaderOne, Thread.State.BLOCKED, true);
		awaitState(loaderTwo, Thread.State.BLOCKED, true);
		System.out.println("both carriers are pinned by class-loading virtual threads waiting for the ARCHIVE_MONITOR");

		RELEASE_FILE_LOCK.countDown();
		fileReader.join();
		Thread.sleep(1000);

		awaitState(loaderOne, Thread.State.BLOCKED, false);
		awaitState(loaderTwo, Thread.State.BLOCKED, false);
		System.out.println("both carriers are still pinned by class-loading virtual threads waiting for the ARCHIVE_MONITOR");

		if (!monitorOwner.isAlive()) {
			throw new AssertionError("No starvation reproduced; run with -XX:ActiveProcessorCount=2");
		}

		printRelevantThreads();
		System.out.println("REPRODUCED; the FILE_LOCK is free, but vt-monitor-owner cannot remount to release the ARCHIVE_MONITOR");
		System.out.println("The process intentionally remains hung. Now it's time to capture something with jcmd");
		System.out.println("For example with; jcmd " + ProcessHandle.current().pid() + " Thread.dump_to_file -format=json dump.json");
		monitorOwner.join();
	}

	private static Thread startClassLoad(String name, ClassLoader loader, String className) {
		return Thread.ofVirtual().name(name).start(() -> {
			try {
				Class.forName(className, true, loader);
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	private static void awaitState(Thread thread, Thread.State expected, boolean doThrow) throws InterruptedException {
		for (int i = 0; i < 200 && thread.getState() != expected; i++) {
			Thread.sleep(10);
		}
		System.out.println(thread.getName() + " state is " + thread.getState());
		if (doThrow && thread.getState() != expected) {
			throw new AssertionError(thread.getName() + " is " + thread.getState() + ", expected " + expected);
		}
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			latch.await();
		}
		catch (Exception e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}

	private static void printRelevantThreads() {
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		for (ThreadInfo info : threads.dumpAllThreads(true, true)) {
			if (info.getThreadName().startsWith("ForkJoinPool") || info.getThreadName().equals("file-reader")) {
				System.out.println(info);
			}
		}
	}

	private static final class BlockingClassLoader extends ClassLoader {
		static {
			registerAsParallelCapable();
		}

		BlockingClassLoader() {
			super(VirtualThreadClassLoadingStarvation.class.getClassLoader());
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.equals(TargetOne.class.getName()) || name.equals(TargetTwo.class.getName())) {
				synchronized (ARCHIVE_MONITOR) {
					return super.loadClass(name, resolve);
				}
			}
			return super.loadClass(name, resolve);
		}
	}

	private static final class TargetOne {
	}

	private static final class TargetTwo {
	}
}