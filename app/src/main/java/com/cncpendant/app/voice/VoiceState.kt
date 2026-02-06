package com.cncpendant.app.voice

/**
 * Sealed class representing the various states of the voice control system.
 * Using a state machine pattern simplifies state management and makes transitions explicit.
 */
sealed class VoiceState {
    
    /**
     * Initial/idle state - voice system is initialized but not actively listening
     */
    data object Idle : VoiceState()
    
    /**
     * Actively listening for voice commands
     */
    data class Listening(
        val isContinuous: Boolean = false
    ) : VoiceState()
    
    /**
     * Wake word was detected, waiting for the actual command
     * Has a timeout to return to idle if no command follows
     */
    data class AwaitingCommandAfterWake(
        val timeoutMillis: Long = 10000L
    ) : VoiceState()
    
    /**
     * A command has been parsed and is awaiting user confirmation (yes/no)
     */
    data class AwaitingConfirmation(
        val pendingCommand: VoiceCommand,
        val chainedCommands: List<VoiceCommand> = emptyList()
    ) : VoiceState()
    
    /**
     * Executing a single command
     */
    data class ExecutingCommand(
        val command: VoiceCommand
    ) : VoiceState()
    
    /**
     * Executing a chain of commands sequentially
     */
    data class ExecutingChain(
        val commands: List<VoiceCommand>,
        val currentIndex: Int = 0
    ) : VoiceState() {
        val currentCommand: VoiceCommand? 
            get() = commands.getOrNull(currentIndex)
        val isComplete: Boolean 
            get() = currentIndex >= commands.size
        val progress: String 
            get() = "${currentIndex + 1}/${commands.size}"
    }
    
    /**
     * Text-to-speech is speaking, may resume listening after
     */
    data class Speaking(
        val message: String,
        val resumeListeningAfter: Boolean = true,
        val previousState: VoiceState = Idle
    ) : VoiceState()
    
    /**
     * Waiting for unlock confirmation after soft reset
     */
    data object AwaitingUnlock : VoiceState()
    
    /**
     * Error state with optional recovery
     */
    data class Error(
        val message: String,
        val canRetry: Boolean = true
    ) : VoiceState()
    
    /**
     * Check if the state allows accepting new voice input
     */
    fun canAcceptInput(): Boolean = when (this) {
        is Listening, is AwaitingCommandAfterWake, is AwaitingConfirmation -> true
        else -> false
    }
    
    /**
     * Check if the state allows starting a new command
     */
    fun canStartNewCommand(): Boolean = when (this) {
        is Idle, is Listening, is AwaitingCommandAfterWake -> true
        else -> false
    }
    
    /**
     * Human-readable state description for logging/debugging
     */
    fun description(): String = when (this) {
        is Idle -> "Idle"
        is Listening -> if (isContinuous) "Listening (continuous)" else "Listening"
        is AwaitingCommandAfterWake -> "Awaiting command after wake word"
        is AwaitingConfirmation -> "Awaiting confirmation: ${pendingCommand.description}"
        is ExecutingCommand -> "Executing: ${command.description}"
        is ExecutingChain -> "Executing chain $progress: ${currentCommand?.description ?: "complete"}"
        is Speaking -> "Speaking: $message"
        is AwaitingUnlock -> "Awaiting unlock"
        is Error -> "Error: $message"
    }
}

/**
 * State machine events that can trigger state transitions
 */
sealed class VoiceEvent {
    data object StartListening : VoiceEvent()
    data object StopListening : VoiceEvent()
    data class WakeWordDetected(val word: String) : VoiceEvent()
    data object WakeWordTimeout : VoiceEvent()
    data class CommandParsed(val command: VoiceCommand, val requiresConfirmation: Boolean) : VoiceEvent()
    data class ChainedCommandsParsed(val commands: List<VoiceCommand>, val requiresConfirmation: Boolean) : VoiceEvent()
    data object ConfirmationReceived : VoiceEvent()
    data object CancellationReceived : VoiceEvent()
    data object CommandExecuted : VoiceEvent()
    data object ChainStepCompleted : VoiceEvent()
    data object ChainCompleted : VoiceEvent()
    data class SpeakingStarted(val message: String) : VoiceEvent()
    data object SpeakingCompleted : VoiceEvent()
    data class ErrorOccurred(val message: String) : VoiceEvent()
    data object Reset : VoiceEvent()
}

/**
 * Manages voice state transitions with validation
 */
class VoiceStateMachine(
    initialState: VoiceState = VoiceState.Idle
) {
    var currentState: VoiceState = initialState
        private set
    
    private val listeners = mutableListOf<(VoiceState, VoiceState) -> Unit>()
    
    /**
     * Add a listener for state changes
     */
    fun addStateChangeListener(listener: (oldState: VoiceState, newState: VoiceState) -> Unit) {
        listeners.add(listener)
    }
    
    /**
     * Remove a state change listener
     */
    fun removeStateChangeListener(listener: (VoiceState, VoiceState) -> Unit) {
        listeners.remove(listener)
    }
    
    /**
     * Process an event and transition to the appropriate state
     */
    fun processEvent(event: VoiceEvent): VoiceState {
        val oldState = currentState
        val newState = calculateNextState(currentState, event)
        
        if (newState != oldState) {
            currentState = newState
            listeners.forEach { it(oldState, newState) }
        }
        
        return newState
    }
    
    /**
     * Force transition to a specific state (use sparingly)
     */
    fun forceState(state: VoiceState) {
        val oldState = currentState
        currentState = state
        listeners.forEach { it(oldState, state) }
    }
    
    /**
     * Calculate the next state based on current state and event
     */
    private fun calculateNextState(current: VoiceState, event: VoiceEvent): VoiceState {
        return when (event) {
            is VoiceEvent.StartListening -> {
                VoiceState.Listening(isContinuous = false)
            }
            
            is VoiceEvent.StopListening -> {
                when (current) {
                    is VoiceState.Listening -> VoiceState.Idle
                    is VoiceState.AwaitingCommandAfterWake -> VoiceState.Idle
                    else -> current
                }
            }
            
            is VoiceEvent.WakeWordDetected -> {
                VoiceState.AwaitingCommandAfterWake()
            }
            
            is VoiceEvent.WakeWordTimeout -> {
                when (current) {
                    is VoiceState.AwaitingCommandAfterWake -> VoiceState.Idle
                    else -> current
                }
            }
            
            is VoiceEvent.CommandParsed -> {
                if (event.requiresConfirmation) {
                    VoiceState.AwaitingConfirmation(event.command)
                } else {
                    VoiceState.ExecutingCommand(event.command)
                }
            }
            
            is VoiceEvent.ChainedCommandsParsed -> {
                if (event.requiresConfirmation && event.commands.isNotEmpty()) {
                    VoiceState.AwaitingConfirmation(
                        pendingCommand = event.commands.first(),
                        chainedCommands = event.commands
                    )
                } else if (event.commands.isNotEmpty()) {
                    VoiceState.ExecutingChain(event.commands)
                } else {
                    current
                }
            }
            
            is VoiceEvent.ConfirmationReceived -> {
                when (current) {
                    is VoiceState.AwaitingConfirmation -> {
                        if (current.chainedCommands.isNotEmpty()) {
                            VoiceState.ExecutingChain(current.chainedCommands)
                        } else {
                            VoiceState.ExecutingCommand(current.pendingCommand)
                        }
                    }
                    else -> current
                }
            }
            
            is VoiceEvent.CancellationReceived -> {
                when (current) {
                    is VoiceState.AwaitingConfirmation -> VoiceState.Idle
                    is VoiceState.ExecutingChain -> VoiceState.Idle
                    else -> current
                }
            }
            
            is VoiceEvent.CommandExecuted -> {
                when (current) {
                    is VoiceState.ExecutingCommand -> VoiceState.Idle
                    else -> current
                }
            }
            
            is VoiceEvent.ChainStepCompleted -> {
                when (current) {
                    is VoiceState.ExecutingChain -> {
                        val nextIndex = current.currentIndex + 1
                        if (nextIndex >= current.commands.size) {
                            VoiceState.Idle
                        } else {
                            current.copy(currentIndex = nextIndex)
                        }
                    }
                    else -> current
                }
            }
            
            is VoiceEvent.ChainCompleted -> {
                VoiceState.Idle
            }
            
            is VoiceEvent.SpeakingStarted -> {
                VoiceState.Speaking(
                    message = event.message,
                    previousState = current
                )
            }
            
            is VoiceEvent.SpeakingCompleted -> {
                when (current) {
                    is VoiceState.Speaking -> {
                        if (current.resumeListeningAfter && current.previousState is VoiceState.Listening) {
                            current.previousState
                        } else {
                            VoiceState.Idle
                        }
                    }
                    else -> current
                }
            }
            
            is VoiceEvent.ErrorOccurred -> {
                VoiceState.Error(event.message)
            }
            
            is VoiceEvent.Reset -> {
                VoiceState.Idle
            }
        }
    }
}
