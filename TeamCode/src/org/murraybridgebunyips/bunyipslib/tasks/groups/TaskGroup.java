package org.murraybridgebunyips.bunyipslib.tasks.groups;

import org.murraybridgebunyips.bunyipslib.Dbg;
import org.murraybridgebunyips.bunyipslib.EmergencyStop;
import org.murraybridgebunyips.bunyipslib.tasks.bases.Task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/**
 * A group of tasks.
 * Users must be careful to ensure they do not allocate tasks that use the same subsystems when
 * running in parallel (all task groups except SequentialTaskGroup), otherwise hardware will
 * try to take commands from multiple tasks at once. In WPILib, this is handled by mentioning
 * requirements, but the nature of the Task system doesn't demand the need to integrate this.
 *
 * @author Lucas Bubner, 2024
 */
public abstract class TaskGroup extends Task {
    protected final ArrayList<Task> tasks = new ArrayList<>();
    private final HashSet<Task> attachedTasks = new HashSet<>();

    protected TaskGroup(Task... tasks) {
        super(0.0);
        this.tasks.addAll(Arrays.asList(tasks));
        if (tasks.length == 0) {
            throw new EmergencyStop("TaskGroup created with no tasks.");
        }
    }

    protected final void executeTask(Task task) {
        // Do not manage a task if it is already attached to a subsystem being managed there
        if (attachedTasks.contains(task)) return;
        task.getDependency().ifPresent(dependency -> {
            dependency.setCurrentTask(task);
            attachedTasks.add(task);
        });
        // Otherwise we can just run the task outright
        if (!task.hasDependency()) task.run();
    }

    protected final void finishAllTasks() {
        for (Task task : tasks) {
            task.finishNow();
        }
    }

    @Override
    public final void init() {
        // no-op
    }

    @Override
    public final void onFinish() {
        // no-op
    }

    @Override
    protected void onReset() {
        for (Task task : tasks) {
            task.reset();
        }
        attachedTasks.clear();
    }
}
