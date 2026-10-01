package com.palash.opencodeserver.backend.install

import com.palash.opencodeserver.backend.core.BackendError
import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.DependencyReport
import com.palash.opencodeserver.backend.core.DependencyState
import com.palash.opencodeserver.backend.core.SetupCheckpoint
import com.palash.opencodeserver.backend.core.SetupProgress
import com.palash.opencodeserver.backend.core.SetupStep
import com.palash.opencodeserver.backend.deps.DependencyCatalog
import com.palash.opencodeserver.backend.deps.DependencyId
import com.palash.opencodeserver.backend.deps.DependencyManager
import com.palash.opencodeserver.backend.log.LogCategory
import com.palash.opencodeserver.backend.log.LogManager
import com.palash.opencodeserver.backend.opencode.OpenCodeConfigManager
import com.palash.opencodeserver.backend.opencode.OpenCodeManager
import com.palash.opencodeserver.backend.runtime.EnvironmentManager
import com.palash.opencodeserver.backend.runtime.RuntimeLayout
import com.palash.opencodeserver.backend.security.CredentialManager
import com.palash.opencodeserver.backend.userland.UserlandProviderRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spec 6/99/100/60 - the setup orchestrator.
 *
 * This is what turns a catalogue into the ten-line experience the spec describes:
 * ```
 * ✓ Android environment detected
 * ✓ CPU architecture detected
 * ✓ Terminal runtime installed
 * ...
 * ```
 *
 * Three properties make it honest:
 *  - **Resumable** (spec 99/100): progress is persisted as a [SetupCheckpoint], so a run interrupted
 *    at NODE_READY resumes at OPENCODE_READY instead of reinstalling everything.
 *  - **Verified** (spec 113): each step's success is determined by a post-install check, not by the
 *    install command exiting zero.
 *  - **Advanced-detail only** (spec 6/97): the UI gets a human line plus, separately, the raw
 *    commands for the expandable Advanced view.
 */
class SetupOrchestrator(
    private val context: android.content.Context,
    private val layout: RuntimeLayout,
    private val environment: EnvironmentManager,
    private val dependencies: DependencyManager,
    private val openCode: OpenCodeManager,
    private val openCodeConfig: OpenCodeConfigManager,
    private val userlandRegistry: UserlandProviderRegistry,
    private val credentials: CredentialManager,
    private val log: LogManager
) {

    private val _progress = MutableStateFlow<SetupProgress?>(null)
    val progress: StateFlow<SetupProgress?> = _progress.asStateFlow()

    /** Spec 99 - resume state, persisted so it survives the app being killed mid-setup. */
    private val _checkpoint = MutableStateFlow(loadCheckpoint())
    val checkpoint: StateFlow<SetupCheckpoint> = _checkpoint.asStateFlow()

    private val advancedCommands = mutableListOf<String>()

    /** The steps shown in the wizard, in order. */
    private val plan: List<SetupStep> = listOf(
        SetupStep.WELCOME,
        SetupStep.CHECKING_DEVICE,
        SetupStep.RUNTIME,
        SetupStep.PACKAGE_MANAGER,
        SetupStep.CORE_TOOLS,
        SetupStep.GIT,
        SetupStep.NODE,
        SetupStep.OPENCODE,
        SetupStep.CONFIG,
        SetupStep.SERVER,
        SetupStep.PROVIDER
    )

    /** Wizard step -> catalogue entry, so install order and UI order cannot drift apart. */
    private val toolSteps: List<Pair<SetupStep, DependencyId>> = listOf(
        SetupStep.CORE_TOOLS to DependencyId.CORE_UTILITIES,
        SetupStep.GIT to DependencyId.GIT,
        SetupStep.NODE to DependencyId.NODE
    )

    /**
     * Spec 6 - run (or resume) setup, emitting progress as it goes.
     *
     * [force] re-runs every step; without it, steps whose checkpoint is already reached are skipped,
     * which is what makes a resumed setup fast.
     */
    fun run(force: Boolean = false, projectDirectory: String? = null): Flow<SetupProgress> = kotlinx.coroutines.flow.flow {
        layout.ensureDirectories()?.let { error ->
            emit(terminalFailure(SetupStep.CHECKING_DEVICE, error))
            return@flow
        }

        // ---- Step 2: checking the device ---------------------------------------------------
        advance(SetupStep.CHECKING_DEVICE, "Checking device compatibility", force)
        advancedCommands += environment.describeDevice()

        val selection = userlandRegistry.select()
        if (!selection.isAvailable) {
            emit(
                terminalFailure(
                    SetupStep.CHECKING_DEVICE,
                    BackendError.ArchitectureUnsupported(
                        detected = environment.report.value?.architecture?.displayName ?: "this device",
                        required = "A bundled Linux userland",
                        technical = selection.reason + "\n" + selection.alternatives.joinToString("\n")
                    )
                )
            )
            return@flow
        }
        emit(checkpointed(SetupStep.CHECKING_DEVICE, SetupCheckpoint.STARTED))

        // ---- Step 3: the Linux runtime -------------------------------------------------------
        advance(SetupStep.RUNTIME, "Installing the Linux runtime", force)
        if (force || !reached(SetupCheckpoint.RUNTIME_READY)) {
            when (val provider = selection.provider) {
                null -> {
                    emit(terminalFailure(SetupStep.RUNTIME, BackendError.Runtime("No userland available")))
                    return@flow
                }
                else -> {
                    when (val installed = provider.install()) {
                        is BackendResult.Failure -> {
                            emit(terminalFailure(SetupStep.RUNTIME, installed.error))
                            return@flow
                        }
                        is BackendResult.Success -> {
                            provider.activate(currentEnvironmentProvider())
                            markReached(SetupCheckpoint.RUNTIME_READY)
                        }
                    }
                }
            }
        }
        emit(checkpointed(SetupStep.RUNTIME, SetupCheckpoint.RUNTIME_READY))

        // ---- Step 4: the package manager ------------------------------------------------------
        advance(SetupStep.PACKAGE_MANAGER, "Preparing the package manager", force)
        if (force || !reached(SetupCheckpoint.PACKAGE_MANAGER_READY)) {
            val detected = dependencies.packageManager()
            advancedCommands += "package manager: ${detected.displayName}"
            markReached(SetupCheckpoint.PACKAGE_MANAGER_READY)
        }
        emit(checkpointed(SetupStep.PACKAGE_MANAGER, SetupCheckpoint.PACKAGE_MANAGER_READY))

        // ---- Steps 5-7: tools, each declared in the catalogue -----------------------------------
        for ((step, dependencyId) in toolSteps) {
            advance(step, "Installing ${DependencyCatalog.byId(dependencyId).displayName}", force)

            if (force || !reached(checkpointFor(dependencyId))) {
                val spec = DependencyCatalog.byId(dependencyId)
                // Spec 74 - refuse before starting a large download.
                val free = layout.freeBytes()
                if (spec.estimatedBytes > 0 && free in 1 until spec.estimatedBytes) {
                    emit(
                        terminalFailure(
                            step,
                            BackendError.Storage(spec.estimatedBytes, free)
                        )
                    )
                    return@flow
                }

                when (val result = dependencies.installDependency(spec)) {
                    is BackendResult.Failure -> {
                        // An optional tool failing is a warning, not a hard stop.
                        if (spec.optional || !spec.requiredForServer) {
                            log.warn(
                                LogCategory.DEPENDENCY,
                                "Optional dependency unavailable",
                                "${spec.displayName}: ${result.error.technical}"
                            )
                            emit(warning(step, result.error))
                            continue
                        }
                        emit(terminalFailure(step, result.error))
                        return@flow
                    }
                    is BackendResult.Success -> {
                        advancedCommands += "${spec.id.name}: ${result.value.installedVersion ?: "installed"}"
                        markReached(checkpointFor(dependencyId))
                    }
                }
            }
            emit(checkpointed(step, checkpointFor(dependencyId)))
        }

        // ---- Step 8: OpenCode ---------------------------------------------------------------------
        advance(SetupStep.OPENCODE, "Installing OpenCode", force)
        if (force || !reached(SetupCheckpoint.OPENCODE_READY)) {
            when (val installed = openCode.install()) {
                is BackendResult.Failure -> {
                    emit(terminalFailure(SetupStep.OPENCODE, installed.error))
                    return@flow
                }
                is BackendResult.Success -> {
                    advancedCommands += "opencode ${installed.value}"
                    markReached(SetupCheckpoint.OPENCODE_READY)
                }
            }
        }
        emit(checkpointed(SetupStep.OPENCODE, SetupCheckpoint.OPENCODE_READY))

        // ---- Step 9: configuration ------------------------------------------------------------------
        advance(SetupStep.CONFIG, "Creating the OpenCode configuration", force)
        if (force || !reached(SetupCheckpoint.CONFIG_READY)) {
            when (val config = openCodeConfig.ensureConfig(layout.openCodeConfigDir)) {
                is BackendResult.Failure -> {
                    emit(terminalFailure(SetupStep.CONFIG, config.error))
                    return@flow
                }
                is BackendResult.Success -> {
                    advancedCommands += "config: ${config.value.absolutePath}"
                    // Spec 15 - ensure the server password exists now, before the first start.
                    credentials.serverPassword()
                    markReached(SetupCheckpoint.CONFIG_READY)
                }
            }
        }
        emit(checkpointed(SetupStep.CONFIG, SetupCheckpoint.CONFIG_READY))

        // ---- Step 10: reach a ready environment, without starting the server -------------------------
        advance(SetupStep.SERVER, "Preparing the local server", force)
        advancedCommands += "runtime: ${layout.runtimeRoot.absolutePath}"
        advancedCommands += "home: ${layout.home.absolutePath}"
        val report = environment.evaluate(projectDirectory)
        emit(
            SetupProgress(
                step = SetupStep.SERVER,
                stepIndex = plan.indexOf(SetupStep.SERVER),
                stepCount = plan.size,
                headline = "Your local coding environment is ready.",
                detail = "Your local coding environment is ready.",
                percent = 92,
                // Each component's real state, so the wizard's summary reflects what was installed.
                componentStates = reportToStates(report),
                advancedCommands = advancedCommands.toList(),
                checkpoint = SetupCheckpoint.CONFIG_READY
            )
        )
        markReached(SetupCheckpoint.CONFIG_READY)

        // ---- Step 11: the provider prompt --------------------------------------------------------------
        advance(SetupStep.PROVIDER, "Connect an AI provider", force)
        emit(
            SetupProgress(
                step = SetupStep.PROVIDER,
                stepIndex = plan.indexOf(SetupStep.PROVIDER),
                stepCount = plan.size,
                headline = "Connect an AI provider",
                detail = "Add an API key so OpenCode can answer your prompts.",
                percent = 100,
                componentStates = emptyMap(),
                advancedCommands = advancedCommands.toList(),
                checkpoint = SetupCheckpoint.COMPLETE,
                isTerminal = false
            )
        )
        markReached(SetupCheckpoint.COMPLETE)

        // ---- Done -------------------------------------------------------------------------------------------
        emit(
            SetupProgress(
                step = SetupStep.DONE,
                stepIndex = plan.size,
                stepCount = plan.size,
                headline = "Setup complete",
                detail = "Your local coding environment is ready.",
                percent = 100,
                componentStates = emptyMap(),
                advancedCommands = advancedCommands.toList(),
                checkpoint = SetupCheckpoint.COMPLETE,
                isTerminal = true
            )
        )
    }

    /** Spec 117 - repair a single component without resetting the rest. */
    fun repair(dependencyId: DependencyId): Flow<SetupProgress> = kotlinx.coroutines.flow.flow {
        val spec = DependencyCatalog.byId(dependencyId)
        val step = stepFor(dependencyId)

        advance(step, "Repairing ${spec.displayName}", force = true)
        emit(
            when (val result = dependencies.repair(spec)) {
                is BackendResult.Success -> checkpointed(step, checkpointFor(dependencyId))
                is BackendResult.Failure -> terminalFailure(step, result.error)
            }
        )
    }

    /** Spec 99 - "Setup was interrupted. Checking installed components..." */
    fun describeResumeState(): String {
        val checkpoint = _checkpoint.value
        return if (checkpoint == SetupCheckpoint.STARTED) {
            "Setup has not been completed yet."
        } else {
            "Setup was interrupted after \"${checkpoint.stageLabel}\". Checking installed components..."
        }
    }

    // ---- internals ----------------------------------------------------------------------

    /** Injected by the backend so the orchestrator can activate the chosen userland. */
    @Volatile
    private var environmentProvider: (() -> com.palash.opencodeserver.backend.runtime.EnvironmentBuilder)? = null

    fun attachEnvironmentProvider(provider: () -> com.palash.opencodeserver.backend.runtime.EnvironmentBuilder) {
        environmentProvider = provider
    }

    private fun currentEnvironmentProvider(): com.palash.opencodeserver.backend.runtime.EnvironmentBuilder =
        environmentProvider?.invoke()
            ?: throw IllegalStateException("SetupOrchestrator has no environment provider attached")

    private fun advance(step: SetupStep, detail: String, force: Boolean) {
        val index = plan.indexOf(step)
        _progress.value = SetupProgress(
            step = step,
            stepIndex = index,
            stepCount = plan.size,
            headline = step.title,
            detail = detail,
            percent = if (plan.isEmpty()) 0 else (index * 100 / plan.size),
            advancedCommands = advancedCommands.toList(),
            checkpoint = _checkpoint.value
        )
    }

    private fun checkpointed(step: SetupStep, checkpoint: SetupCheckpoint): SetupProgress {
        val index = plan.indexOf(step)
        return SetupProgress(
            step = step,
            stepIndex = index,
            stepCount = plan.size,
            headline = step.title,
            // Spec 97 - the normal UI says "Installing OpenCode...", never the raw command.
            detail = "${step.title} ready",
            percent = if (plan.isEmpty()) 0 else ((index + 1) * 100 / plan.size),
            advancedCommands = advancedCommands.toList(),
            checkpoint = checkpoint
        )
    }

    private fun warning(step: SetupStep, error: BackendError): SetupProgress {
        val index = plan.indexOf(step)
        return SetupProgress(
            step = step,
            stepIndex = index,
            stepCount = plan.size,
            headline = step.title,
            detail = "Optional: ${error.userMessage.lineSequence().firstOrNull().orEmpty()}",
            percent = if (plan.isEmpty()) 0 else ((index + 1) * 100 / plan.size),
            advancedCommands = advancedCommands.toList() + (error.technical?.let { "note: $it" } ?: "note: unavailable"),
            checkpoint = _checkpoint.value
        )
    }

    private fun terminalFailure(step: SetupStep, error: BackendError): SetupProgress {
        val index = plan.indexOf(step)
        _checkpoint.value = _checkpoint.value // unchanged; the next run resumes from here
        log.error(LogCategory.RUNTIME, "Setup failed at ${step.name}", error.technical)
        return SetupProgress(
            step = step,
            stepIndex = index,
            stepCount = plan.size,
            // Spec 43 - a real explanation, with a path forward.
            headline = error.userTitle,
            detail = error.userMessage,
            percent = if (plan.isEmpty()) 0 else (index * 100 / plan.size),
            advancedCommands = advancedCommands.toList(),
            checkpoint = _checkpoint.value,
            error = error,
            isTerminal = true
        )
    }

    private fun reached(checkpoint: SetupCheckpoint): Boolean = _checkpoint.value >= checkpoint

    private fun markReached(checkpoint: SetupCheckpoint) {
        if (_checkpoint.value < checkpoint) {
            _checkpoint.value = checkpoint
            persistCheckpoint(checkpoint)
        }
    }

    private fun checkpointFor(dependencyId: DependencyId): SetupCheckpoint =
        DependencyCatalog.checkpointFor(dependencyId)

    private fun stepFor(dependencyId: DependencyId): SetupStep =
        toolSteps.firstOrNull { it.second == dependencyId }?.first ?: SetupStep.RUNTIME

    /** Map an environment report's per-dependency states into the shape the wizard renders. */
    private fun reportToStates(
        report: com.palash.opencodeserver.backend.core.EnvironmentReport
    ): Map<String, DependencyState> {
        val states = linkedMapOf<String, DependencyState>()
        states[DependencyId.RUNTIME.name] = if (report.runtimeInstalled) {
            DependencyState.INSTALLED
        } else {
            DependencyState.MISSING
        }
        report.shell?.let { states[DependencyId.SHELL.name] = DependencyState.INSTALLED }
        report.packageManager?.let { states[DependencyId.PACKAGE_MANAGER.name] = DependencyState.INSTALLED }
        report.gitVersion?.let { states[DependencyId.GIT.name] = DependencyState.INSTALLED }
        report.nodeVersion?.let { states[DependencyId.NODE.name] = DependencyState.INSTALLED }
        report.openCodeVersion?.let { states[DependencyId.OPENCODE.name] = DependencyState.INSTALLED }
        states[DependencyId.TEST_PROJECT.name] = if (report.projectDirectory != null) {
            DependencyState.INSTALLED
        } else {
            DependencyState.MISSING
        }
        return states
    }

    private fun persistCheckpoint(checkpoint: SetupCheckpoint) {
        runCatching { layout.checkpointFile.writeText(checkpoint.name, Charsets.UTF_8) }
    }

    private fun loadCheckpoint(): SetupCheckpoint = runCatching {
        if (layout.checkpointFile.exists()) {
            SetupCheckpoint.valueOf(layout.checkpointFile.readText(Charsets.UTF_8).trim())
        } else {
            SetupCheckpoint.STARTED
        }
    }.getOrDefault(SetupCheckpoint.STARTED)

    /** Spec 58 - reset everything. Destructive, and only ever called after confirmation. */
    fun resetEnvironment(confirm: Boolean): BackendResult<Unit> {
        if (!confirm) {
            return BackendResult.Failure(
                BackendError.Unknown(
                    technical = "reset refused without confirmation",
                    userTitle = "Confirmation required.",
                    userMessage = "Resetting the environment removes installed tools."
                )
            )
        }

        // Spec 119 - back up configuration before destroying anything.
        val backup = openCodeConfig.backup(layout.openCodeConfigDir, layout.backupDir)

        return try {
            // Spec 58 - keep user projects. Only runtime files are removed.
            layout.binDir.deleteRecursively()
            layout.tmpDir.deleteRecursively()
            layout.userlandDir.deleteRecursively()
            layout.checkpointFile.delete()
            layout.serverRegistrationFile.delete()

            layout.ensureDirectories()
            _checkpoint.value = SetupCheckpoint.STARTED
            persistCheckpoint(SetupCheckpoint.STARTED)
            log.info(
                LogCategory.RUNTIME,
                "Development environment reset; projects were kept",
                "configBackup=${backup.valueOrNull()?.name ?: "none"}"
            )
            BackendResult.Success(Unit)
        } catch (e: Exception) {
            BackendResult.Failure(BackendError.wrap(e))
        }
    }
}