package org.murraybridgebunyips.bunyipslib.tasks;

import org.murraybridgebunyips.bunyipslib.BunyipsSubsystem;
import org.murraybridgebunyips.bunyipslib.tasks.bases.OnceTask;

/**
 * A task to run a callback before immediately completing.
 * <p>
 * {@code new RunTask(() -> telemetry.add("Hello world"));}
 */
public class RunTask extends OnceTask {
    private final Runnable callback;

    /**
     * Run the given callback immediately.
     *
     * @param callback The callback to run
     */
    public RunTask(Runnable callback) {
        this.callback = callback;
        withName("Run");
    }

    /**
     * Run nothing and complete, this is useful as a placeholder task.
     */
    public RunTask() {
        this(() -> {
        });
        withName("Run Nothing");
    }

    /**
     * Run the given callback immediately, with a dependency on the given subsystem.
     *
     * @param callback            The callback to run.
     * @param dependencySubsystem The subsystem to run this on.
     * @param override            Whether to override conflicting tasks.
     */
    public RunTask(Runnable callback, BunyipsSubsystem dependencySubsystem, boolean override) {
        super(dependencySubsystem, override);
        this.callback = callback;
        withName("Run");
    }

    @Override
    protected void runOnce() {
        callback.run();
    }
}
