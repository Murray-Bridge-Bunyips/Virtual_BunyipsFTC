package org.murraybridgebunyips.bunyipslib

import com.acmerobotics.dashboard.FtcDashboard
import com.acmerobotics.dashboard.telemetry.MultipleTelemetry
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import org.firstinspires.ftc.robotcore.external.Telemetry
import org.firstinspires.ftc.robotcore.external.Telemetry.Item
import org.murraybridgebunyips.bunyipslib.Text.formatString
import org.murraybridgebunyips.bunyipslib.Text.round
import org.murraybridgebunyips.bunyipslib.roadrunner.util.LynxModuleUtil
import org.murraybridgebunyips.deps.BuildConfig
import java.util.Objects
import kotlin.collections.HashMap
import kotlin.math.roundToInt

/**
 * Base class for all OpModes that provides a number of useful methods and utilities for development.
 * Includes a lifecycle that is similar to an iterative lifecycle, but includes logging, error catching,
 * and abstraction to provide phased code execution.
 *
 * @author Lucas Bubner, 2023
 */
abstract class BunyipsOpMode : LinearOpMode() {
    lateinit var movingAverageTimer: MovingAverageTimer
        private set

    private var operationsCompleted = false
    private var operationsPaused = false

    private var opModeStatus = "idle"
    private lateinit var overheadTelemetry: Item
    private var telemetryQueue = 0

    private lateinit var telem: MultipleTelemetry
    private val retainedObjects = HashMap<Int, Item>()

    companion object {
        /**
         * The instance of the current BunyipsOpMode. This is set automatically by the OpMode lifecycle.
         * This can be used instead of dependency injection to access the current OpMode, as it is a singleton.
         *
         * BunyipsComponent and Task internally use this to grant access to the current OpMode through
         * the `opMode` property. You must ensure all Tasks and BunyipsComponents are instantiated during runtime,
         * (such as during onInit()), otherwise this property will be null.
         */
        @JvmStatic
        lateinit var instance: BunyipsOpMode
            private set
    }

    /**
     * Runs upon the pressing of the INIT button on the Driver Station.
     * This is where you should initialise your hardware and other components.
     */
    protected abstract fun onInit()

    /**
     * Run code in a loop AFTER onInit has completed, until
     * start is pressed on the Driver Station or true is returned to this method.
     * If not implemented, the OpMode will continue on as normal and wait for start.
     */
    protected open fun onInitLoop(): Boolean {
        return true
    }

    /**
     * Allow code to execute once after all initialisation has finished.
     * Note: this method is always called even if initialisation is cut short by the driver station.
     */
    protected open fun onInitDone() {
    }

    /**
     * Perform one time operations after start is pressed.
     * Unlike onInitDone, this will only execute once play is hit and not when initialisation is done.
     */
    protected open fun onStart() {
    }

    /**
     * Code to run when the START button is pressed on the Driver Station.
     * This method will be called on each hardware cycle.
     */
    protected abstract fun activeLoop()

    /**
     * Perform one time clean-up operations after the OpMode finishes.
     * This method is not exception protected!
     */
    protected open fun onStop() {
    }

    /**
     * Main method overridden from LinearOpMode that handles the OpMode lifecycle.
     * @throws InterruptedException
     */
    @Throws(InterruptedException::class)
    final override fun runOpMode() {
        // BunyipsOpMode
        instance = this
        try {
            Dbg.log("=============== BunyipsOpMode ${BuildConfig.GIT_COMMIT} ${BuildConfig.GIT_BRANCH} ${BuildConfig.BUILD_TIME} id:${BuildConfig.ID} ===============")
            LynxModuleUtil.ensureMinimumFirmwareVersion(hardwareMap)
            // Bind telemetry to FtcDashboard
            telem = MultipleTelemetry(telemetry, FtcDashboardTelemetry())
            // Separate log from telemetry
            telem.log().add("")
            telem.log()
                .add("bunyipsopmode ${BuildConfig.GIT_COMMIT} ${BuildConfig.GIT_BRANCH} ${BuildConfig.BUILD_TIME}")

            opModeStatus = "setup"
            Dbg.logd("BunyipsOpMode: setting up...")
            telem.log().displayOrder = Telemetry.Log.DisplayOrder.OLDEST_FIRST
            telem.captionValueSeparator = ""
            // Uncap the telemetry log limit to ensure we capture everything
            telem.log().capacity = 999999
            // Ring-buffer timing utility
            movingAverageTimer = MovingAverageTimer()
            // Assign overhead telemetry as it has been configured now
            overheadTelemetry =
                telem.addData("", "BOM: idle | T+0s | 0.000ms | (?) (?)\n")
                    .setRetained(true)
            pushTelemetry()

            opModeStatus = "static_init"
            Dbg.logd("BunyipsOpMode: firing onInit()...")
            // Store telemetry objects raised by onInit() by turning off auto-clear
            setTelemetryAutoClear(false)
            pushTelemetry()
            if (!gamepad1.atRest() || !gamepad2.atRest()) {
                log("warning: a gamepad was not zeroed during init. please ensure controllers zero out correctly.")
            }
            // Run user-defined setup
            try {
                onInit()
            } catch (e: Exception) {
                // All InterruptedExceptions are handled by the FTC SDK and are raised in ErrorUtil
                ErrorUtil.handleCatchAllException(e, ::log)
            }

            opModeStatus = "dynamic_init"
            pushTelemetry()
            Dbg.logd("BunyipsOpMode: starting onInitLoop()...")
            // Run user-defined dynamic initialisation
            while (opModeInInit()) {
                try {
                    // Run until onInitLoop returns true or the OpMode is continued
                    if (onInitLoop()) break
                } catch (e: Exception) {
                    ErrorUtil.handleCatchAllException(e, ::log)
                }
                movingAverageTimer.update()
                pushTelemetry()
            }

            opModeStatus = "finish_init"
            pushTelemetry()
            Dbg.logd("BunyipsOpMode: firing onInitDone()...")
            // Run user-defined final initialisation
            try {
                onInitDone()
            } catch (e: Exception) {
                ErrorUtil.handleCatchAllException(e, ::log)
            }

            // Ready to go.
            opModeStatus = "ready"
            movingAverageTimer.update()
            Dbg.logd("BunyipsOpMode: init cycle completed in ${movingAverageTimer.elapsedTime() / 1000.0} secs")
            telem.addData("BunyipsOpMode: ", "INIT COMPLETE -- PLAY WHEN READY.")
            Dbg.logd("BunyipsOpMode: ready.")
            // Set telemetry to an inert state while we wait for start
            pushTelemetry()
            overheadTelemetry.setValue(
                "BOM: ready | T+0s | ${
                    round(
                        movingAverageTimer.elapsedTime() / 1000.0,
                        2
                    )
                }s init | (?) (?)\n"
            )

            waitForStart()

            // Play button has been pressed
            opModeStatus = "starting"
            setTelemetryAutoClear(true)
            clearTelemetry()
            pushTelemetry()
            movingAverageTimer.reset()
            Dbg.logd("BunyipsOpMode: firing onStart()...")
            try {
                // Run user-defined start operations
                onStart()
            } catch (e: Exception) {
                ErrorUtil.handleCatchAllException(e, ::log)
            }

            opModeStatus = "running"
            Dbg.logd("BunyipsOpMode: starting activeLoop()...")
            while (opModeIsActive() && !operationsCompleted) {
                if (operationsPaused) {
                    // If the OpMode is paused, skip the loop and wait for the next hardware cycle
                    // Timers and telemetry will remain frozen and have to be controlled externally
                    idle()
                    continue
                }
                try {
                    // Run user-defined active loop
                    activeLoop()
                } catch (e: Exception) {
                    ErrorUtil.handleCatchAllException(e, ::log)
                }
                // Update telemetry and timers
                movingAverageTimer.update()
                pushTelemetry()
            }

            opModeStatus = "finished"
            // overheadTelemetry will no longer update, will remain frozen on last value
            pushTelemetry()
            Dbg.logd("BunyipsOpMode: all tasks finished.")
            // Wait for user to hit stop or for the OpMode to be terminated
            while (opModeIsActive()) {
                idle()
            }
        } catch (t: Throwable) {
            Dbg.error("BunyipsOpMode: unhandled throwable! <${t.message}>")
            Dbg.sendStacktrace(t)
            // As this error occurred somewhere not inside user code, we should not swallow it.
            // There is also a chance this error is an instance of Error, in which case we should
            // exit immediately as something has gone very wrong
            throw t
        } finally {
            Dbg.logd("BunyipsOpMode: opmode stop requested. cleaning up...")
            // Ensure all user threads have been told to stop
            Threads.stopAll()
            onStop()
            // Telemetry may be not in a nice state, so we will call our stateful functions
            // such as thread stops and cleanup in onStop() first before updating the status
            opModeStatus = "terminating"
            pushTelemetry()
            Dbg.logd("BunyipsOpMode: exiting...")
        }
    }

    /**
     * Update and push queued telemetry to the Driver Station.
     */
    fun pushTelemetry() {
        if (telem.isAutoClear)
            telemetryQueue = 0

        // Update main DS telemetry
        telemetry.update()

        // Requeue new overhead status message
        val loopTime = round(movingAverageTimer.movingAverage(), 2)
        val overheadStatus =
            "$opModeStatus | T+${
                (movingAverageTimer.elapsedTime() / 1000).roundToInt()
            }s | ${
                if (loopTime <= 0.0) "${
                    round(
                        movingAverageTimer.loopsSec(),
                        1
                    )
                } l/s" else "${loopTime}ms"
            } | ${
                Controller.movementString(gamepad1)
            } ${
                Controller.movementString(gamepad2)
            }\n"
        overheadTelemetry.setValue("BOM: $overheadStatus")
        FtcDashboard.getInstance().telemetry.addData("BOM", overheadStatus + "\n")
        FtcDashboard.getInstance().telemetry.update()
    }

    /**
     * Ensure an exception is not thrown due to the telemetry object being bigger than 250 objects.
     */
    private fun flushTelemetryQueue() {
        telemetryQueue++
        if (telemetryQueue >= 250) {
            // We have to flush out telemetry as the queue is too big
            pushTelemetry()
            if (telem.isAutoClear) {
                // Flush successful
                Dbg.logd("Telemetry queue exceeded 250 messages, auto-pushing to flush...")
            }
        }
    }

    /**
     * Add data to the telemetry object
     * @param value A string to add to telemetry
     */
    fun addTelemetry(value: String) {
        flushTelemetryQueue()
        if (telemetryQueue >= 250 && !telem.isAutoClear) {
            // Auto flush will fail as clearing is not permitted
            // We will send telemetry to the debugger instead as a fallback
            Dbg.log("Telemetry overflow: $value")
        }
        telem.addData("", value)
    }

    /**
     * Add data to the telemetry object using a custom format string
     * @param fstring A format string to add to telemetry
     * @param objs The objects to format into the string
     */
    fun addTelemetry(fstring: String, vararg objs: Any) {
        addTelemetry(formatString(fstring, objs.asList()))
    }

    /**
     * Add retained non-auto-clearing data to the telemetry object
     * @param value A string to add to telemetry
     * @return A hashcode to use with removeRetainedTelemetry
     */
    fun addRetainedTelemetry(value: String): Int {
        flushTelemetryQueue()
        // Retained telemetry will not work with FtcDashboard
        val out = telemetry.addData("", value)
            .setRetained(true)
        val hash = Objects.hash(value, out)
        retainedObjects[hash] = out
        return hash
    }

    /**
     * Add a data to the telemetry object using a custom format string
     * @param fstring A format string to add to telemetry
     * @param objs The objects to format into the string
     * @return A hashcode to use with removeRetainedTelemetry
     */
    fun addRetainedTelemetry(fstring: String, vararg objs: Any): Int {
        return addRetainedTelemetry(formatString(fstring, objs.asList()))
    }

    /**
     * Remove retained entries from the telemetry object.
     * @param items The telemetry item hashcodes to remove generated by addRetainedTelemetry
     */
    fun removeRetainedTelemetry(vararg items: Int) {
        for (item in items) {
            // Retained telemetry/remove item will not work with FtcDashboard
            val res = telemetry.removeItem(
                retainedObjects.getOrDefault(item, FtcDashboardTelemetry.nullItem)
            )
            if (!res) {
                Dbg.logd("Could not find telemetry hashcode to remove: $item")
                return
            }
            retainedObjects.remove(item)
        }
    }

    fun removeRetainedTelemetry(items: List<Int>) {
        removeRetainedTelemetry(*items.toIntArray())
    }

    /**
     * Log a message to the telemetry log
     * @param message The message to log
     */
    fun log(message: String) {
        telem.log().add(message)
    }

    /**
     * Log a message to the telemetry log using a format string
     * @param fstring A format string to add to telemetry
     * @param objs The objects to format into the string
     */
    fun log(fstring: String, vararg objs: Any) {
        return log(formatString(fstring, objs.asList()))
    }

    /**
     * Log a message to the telemetry log
     * @param obj Class where this log was called (name will be prepended to message in lowercase)
     * @param message The message to log
     */
    fun log(obj: Class<*>, message: String) {
        telem.log().add("[${obj.simpleName.toLowerCase()}] $message")
    }

    /**
     * Log a message into the telemetry log
     * @param stck StackTraceElement with information about where this log was called (see Text.getCallingUserCodeFunction())
     * @param message The message to log
     */
    fun log(stck: StackTraceElement, message: String) {
        telem.log().add("[${stck}] $message")
    }

    /**
     * Log a message to the telemetry log using a format string
     * @param obj Class where this log was called (name will be prepended to message in lowercase)
     * @param fstring A format string to add to telemetry
     * @param objs The objects to format into the string
     */
    fun log(obj: Class<*>, fstring: String, vararg objs: Any) {
        return log(obj, formatString(fstring, objs.asList()))
    }

    /**
     * Log a message into the telemetry log using a format string
     * @param stck StackTraceElement with information about where this log was called (see Text.getCallingUserCodeFunction())
     * @param fstring A format string to add to telemetry
     * @param objs The objects to format into the string
     */
    fun log(stck: StackTraceElement, fstring: String, vararg objs: Any) {
        return log(stck, formatString(fstring, objs.asList()))
    }

    /**
     * Reset telemetry data, including retention
     */
    fun resetTelemetry() {
        telemetryQueue = 0
        telem.clearAll()
        retainedObjects.clear()
        // Must reassign the overhead telemetry item
        overheadTelemetry =
            telem.addData("", "BOM: unknown | T+?s | ?ms | (?) (?)")
                .setRetained(true)
    }

    /**
     * Clear telemetry on screen, not including retention
     */
    fun clearTelemetry() {
        telemetryQueue = 0
        telem.clear()
    }

    /**
     * Set telemetry auto clear status
     */
    fun setTelemetryAutoClear(autoClear: Boolean) {
        telem.isAutoClear = autoClear
    }

    /**
     * Get auto-clear status of telemetry
     */
    fun getTelemetryAutoClear(): Boolean {
        return telem.isAutoClear
    }

    /**
     * Call to prevent hardware loop from calling activeLoop(), indicating an OpMode that is finished.
     * This is a dangerous method, as the OpMode will no longer be able to run any main thread code.
     */
    fun finish() {
        if (operationsCompleted) {
            return
        }
        operationsCompleted = true
        Dbg.logd("BunyipsOpMode: activeLoop() terminated by finish().")
        telem.addData("BunyipsOpMode: ", "activeLoop terminated. All operations completed.")
        pushTelemetry()
    }

    /**
     * Call to temporarily halt all activeLoop-related updates from running.
     * Note this will pause all MovingAverageTimer and telemetry updates. These events
     * must be handled manually if needed, which include any conditional calls to resume().
     */
    fun halt() {
        if (operationsPaused) {
            return
        }
        operationsPaused = true
        opModeStatus = "halted"
        Dbg.logd("BunyipsOpMode: activeLoop() halted.")
        telem.addData("BunyipsOpMode: ", "activeLoop halted. Operations paused.")
        pushTelemetry()
    }

    /**
     * Call to resume the activeLoop after a halt() call.
     */
    fun resume() {
        if (!operationsPaused) {
            return
        }
        operationsPaused = false
        opModeStatus = "running"
        Dbg.logd("BunyipsOpMode: activeLoop() resumed.")
        telem.addData("BunyipsOpMode: ", "activeLoop resumed. Operations resumed.")
        pushTelemetry()
    }

    /**
     * Dangerous method: call to shut down the OpMode as soon as possible.
     * This will run any BunyipsOpMode cleanup code.
     */
    fun exit() {
        Dbg.logd("BunyipsOpMode: exiting opmode...")
        finish()
        requestOpModeStop()
    }

    /**
     * Dangerous method: call to IMMEDIATELY terminate the OpMode.
     * This will not run any cleanup code, and should only be used in emergencies.
     */
    fun emergencyStop() {
        Dbg.logd("BunyipsOpMode: emergency stop requested.")
        terminateOpModeNow()
    }
}