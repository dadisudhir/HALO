package com.health.secondbrain.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.health.secondbrain.data.HaloHealthRepository
import com.health.secondbrain.data.HealthAgentContext
import com.health.secondbrain.data.HealthBackendMode
import com.health.secondbrain.data.UserPreferences
import com.health.secondbrain.data.UserProfile
import com.health.secondbrain.health.HealthDashboardUiState
import kotlinx.coroutines.launch

data class YouScreenUiState(
    val profile: UserProfile = UserProfile(
        displayName = "You",
        memberLabel = "HALO health profile",
        avatarInitials = "YOU",
        statusLabel = "Active",
        lastUpdatedText = "Waiting for health data",
    ),
    val preferences: UserPreferences = UserPreferences(
        preferredUnits = "US units",
        remindersEnabled = true,
        dataSharingLabel = "Local SQLite",
    ),
    val dashboard: HealthDashboardUiState = HealthDashboardUiState.Loading,
    val agentContext: HealthAgentContext? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class YouScreenViewModel(
    private val repository: HaloHealthRepository,
) : ViewModel() {

    var uiState by mutableStateOf(YouScreenUiState())
        private set

    fun load(mode: HealthBackendMode, dashboard: HealthDashboardUiState) {
        uiState = uiState.copy(isLoading = true, dashboard = dashboard, error = null)
        viewModelScope.launch {
            val result = runCatching {
                val profile = repository.loadUserProfile()
                val preferences = repository.loadUserPreferences()
                val context = dashboard.organs
                    .firstOrNull { it.id == "heart" }
                    ?.let { repository.loadAgentContext(mode, it) }
                    ?: dashboard.organs.firstOrNull()?.let { repository.loadAgentContext(mode, it) }
                YouScreenUiState(
                    profile = profile,
                    preferences = preferences,
                    dashboard = dashboard,
                    agentContext = context,
                    isLoading = false,
                )
            }
            uiState = result.getOrElse { error ->
                uiState.copy(
                    isLoading = false,
                    dashboard = dashboard,
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    class Factory(private val repository: HaloHealthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return YouScreenViewModel(repository) as T
        }
    }
}
