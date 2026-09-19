# vt-starvation

This is an naive attempt to reproduce boot issue 51463 with plain jdk classes.

- A platform thread holds a `Reetrantlock` representing an in-progress archive file read
- `vt-monitor-owner` enters an object monitor, syncs with `JarFile/NestedJarFile` archive(simulated with ARCHCIVE_MONITOR), and then waits on the `ReetrantLock`. Waiting on the concurrency lock unmounts it from its carrier while it still owns the monitor.
- Two virtual threads enter class loading and try to acquire that monitor. A virtual thread blocked while class loading is pinned to its carrier.
- With exactly two carriers, both become unavailable.
- The platform thread releases `ReentrantLock`. The monitor owner is runnable but cannot remount, so it cannot release the monitor. This is scheduler starvation rather than lock order cycle, and jvm deadlock detection may report it.

There's two classes, `VirtualThreadClassLoadingStarvation` and `PlatformThreadClassLoadingNoStarvation`. Former being the description of above, latter while almost identical is using platform threads with a different awaits and logging.

Plain java, compile classes:

```shell
javac *.java
```

Running native in my linux, there's no constraints.

```shell
$ java VirtualThreadClassLoadingStarvation
PID=1617453, Java=25.0.2+10-69, Processors=12
file-reader acquired the FILE_LOCK
file-reader waiting the RELEASE_FILE_LOCK
vt-monitor-owner state is WAITING
vt-monitor-owner holds the ARCHIVE_MONITOR and is unmounted waiting for the FILE_LOCK
vt-class-loader-1 state is BLOCKED
vt-class-loader-2 state is BLOCKED
both carriers are pinned by class-loading virtual threads waiting for the ARCHIVE_MONITOR
vt-monitor acquired the FILE_LOCK
vt-class-loader-1 state is TERMINATED
vt-class-loader-2 state is TERMINATED
both carriers are still pinned by class-loading virtual threads waiting for the ARCHIVE_MONITOR
Exception in thread "main" java.lang.AssertionError: No starvation reproduced; run with -XX:ActiveProcessorCount=2
	at VirtualThreadClassLoadingStarvation.main(VirtualThreadClassLoadingStarvation.java:64)
```

Simulating constrained environment we can instruct jvm with less processors with _-XX:ActiveProcessorCount_

```shell
$ java -XX:ActiveProcessorCount=2 VirtualThreadClassLoadingStarvation
PID=1619783, Java=25.0.2+10-69, Processors=2
file-reader acquired the FILE_LOCK
file-reader waiting the RELEASE_FILE_LOCK
vt-monitor-owner state is WAITING
vt-monitor-owner holds the ARCHIVE_MONITOR and is unmounted waiting for the FILE_LOCK
vt-class-loader-1 state is BLOCKED
vt-class-loader-2 state is BLOCKED
both carriers are pinned by class-loading virtual threads waiting for the ARCHIVE_MONITOR
vt-class-loader-1 state is BLOCKED
vt-class-loader-2 state is BLOCKED
both carriers are still pinned by class-loading virtual threads waiting for the ARCHIVE_MONITOR
"ForkJoinPool-1-worker-1" daemon prio=5 Id=24 WAITING on java.lang.VirtualThread@3e3abc88 owned by "vt-class-loader-1" Id=25
	at java.base@25.0.2/jdk.internal.vm.Continuation.run(Continuation.java:251)
	-  waiting on java.lang.VirtualThread@3e3abc88
	at java.base@25.0.2/java.lang.VirtualThread.runContinuation(VirtualThread.java:293)
	at java.base@25.0.2/java.lang.VirtualThread$$Lambda/0x000000007c00bae8.run(Unknown Source)
	at java.base@25.0.2/java.util.concurrent.ForkJoinTask$RunnableExecuteAction.compute(ForkJoinTask.java:1750)
	at java.base@25.0.2/java.util.concurrent.ForkJoinTask$RunnableExecuteAction.compute(ForkJoinTask.java:1742)
	at java.base@25.0.2/java.util.concurrent.ForkJoinTask$InterruptibleTask.exec(ForkJoinTask.java:1659)
	at java.base@25.0.2/java.util.concurrent.ForkJoinTask.doExec(ForkJoinTask.java:511)
	at java.base@25.0.2/java.util.concurrent.ForkJoinPool$WorkQueue.topLevelExec(ForkJoinPool.java:1450)
	...


"ForkJoinPool-1-worker-2" daemon prio=5 Id=27 WAITING on java.lang.VirtualThread@6ce253f1 owned by "vt-class-loader-2" Id=26
	at java.base@25.0.2/jdk.internal.vm.Continuation.run(Continuation.java:251)
	-  waiting on java.lang.VirtualThread@6ce253f1
	at java.base@25.0.2/java.lang.VirtualThread.runContinuation(VirtualThread.java:293)
	at java.base@25.0.2/java.lang.VirtualThread$$Lambda/0x000000007c00bae8.run(Unknown Source)
	at java.base@25.0.2/java.util.concurrent.ForkJoinTask$RunnableExecuteAction.compute(ForkJoinTask.java:1750)
	at java.base@25.0.2/java.util.concurrent.ForkJoinTask$RunnableExecuteAction.compute(ForkJoinTask.java:1742)
	at java.base@25.0.2/java.util.concurrent.ForkJoinTask$InterruptibleTask.exec(ForkJoinTask.java:1659)
	at java.base@25.0.2/java.util.concurrent.ForkJoinTask.doExec(ForkJoinTask.java:511)
	at java.base@25.0.2/java.util.concurrent.ForkJoinPool$WorkQueue.topLevelExec(ForkJoinPool.java:1450)
	...


REPRODUCED; the FILE_LOCK is free, but vt-monitor-owner cannot remount to release the ARCHIVE_MONITOR
The process intentionally remains hung. Now it's time to capture something with jcmd
For example with; jcmd 1619783 Thread.dump_to_file -format=json dump.json
```

Moving to platform threads there's no issues:

Normal run:

```shell
$ java PlatformThreadClassLoadingNoStarvation
PID=1623420, Java=25.0.2+10-69, Processors=12
file-reader acquired the FILE_LOCK
file-reader waiting the RELEASE_FILE_LOCK
vt-monitor-owner state is WAITING
vt-monitor-owner holds the ARCHIVE_MONITOR and is unmounted waiting for the FILE_LOCK
vt-class-loader-1 state is BLOCKED
vt-class-loader-2 state is BLOCKED
both carriers are pinned by class-loading platform threads waiting for the ARCHIVE_MONITOR
vt-monitor acquired the FILE_LOCK
vt-class-loader-1 state is TERMINATED
vt-class-loader-2 state is TERMINATED
both carriers are terminated by class-loading platform threads
Exception in thread "main" java.lang.AssertionError: No starvation reproduced; run with -XX:ActiveProcessorCount=2
	at PlatformThreadClassLoadingNoStarvation.main(PlatformThreadClassLoadingNoStarvation.java:64)
```

Restricting processors to 2:

```shell
$ java -XX:ActiveProcessorCount=2 PlatformThreadClassLoadingNoStarvation
PID=1623673, Java=25.0.2+10-69, Processors=2
file-reader acquired the FILE_LOCK
file-reader waiting the RELEASE_FILE_LOCK
vt-monitor-owner state is WAITING
vt-monitor-owner holds the ARCHIVE_MONITOR and is unmounted waiting for the FILE_LOCK
vt-class-loader-1 state is BLOCKED
vt-class-loader-2 state is BLOCKED
both carriers are pinned by class-loading platform threads waiting for the ARCHIVE_MONITOR
vt-monitor acquired the FILE_LOCK
vt-class-loader-1 state is TERMINATED
vt-class-loader-2 state is TERMINATED
both carriers are terminated by class-loading platform threads
Exception in thread "main" java.lang.AssertionError: No starvation reproduced; run with -XX:ActiveProcessorCount=2
	at PlatformThreadClassLoadingNoStarvation.main(PlatformThreadClassLoadingNoStarvation.java:64)
```

Being honest not sure where to go from here. It feels like FJP starvation with virtual threads.

Is this "reproducer" a false positive, I don't know. Differences between platform/virtual threads looks to indicate that there's still some "pinning" issues in a jvm.
