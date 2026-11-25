package rocks.urzahil.taskflow.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rocks.urzahil.taskflow.data.model.AppDate
import rocks.urzahil.taskflow.ui.model.ViewMode

/**
 * The user's settings and lightweight UI state, kept in SharedPreferences. Every value is exposed
 * as a [StateFlow] seeded from storage; setters update the flow and persist. With a null
 * [context] (previews, tests) nothing is persisted and defaults apply.
 */
class UserPreferencesRepository(context: Context?) {

    companion object {
        private const val PREFS_NAME = "taskflow_user_prefs"
        private const val KEY_SHOW_DAILY_PROGRESS = "show_daily_progress"
        private const val KEY_IS_DARK_MODE = "is_dark_mode"
        private const val KEY_USE_DYNAMIC_COLORS = "use_dynamic_colors"
        private const val KEY_SHOW_MONTHLY_TASK_LIST = "show_monthly_task_list"
        private const val KEY_SHOW_MONTHLY_VIEW = "show_monthly_view"
        private const val KEY_SHOW_LIST_VIEW = "show_list_view"
        // Older builds stored inverted "hide_*" flags; they are read only when no "show_*" value exists.
        private const val KEY_LEGACY_HIDE_MONTHLY_TASK_LIST = "hide_monthly_task_list"
        private const val KEY_LEGACY_HIDE_MONTHLY_VIEW = "hide_monthly_view"
        private const val KEY_LEGACY_HIDE_LIST_VIEW = "hide_list_view"
        private const val KEY_ROLLOVER_CLEANUP_ENABLED = "rollover_cleanup_enabled"
        private const val KEY_LAST_ROLLOVER_DATE = "last_rollover_date"
        private const val KEY_LAST_ROLLOVER_TIMESTAMP = "last_rollover_timestamp"
        private const val KEY_LAST_AUTOMATIC_ROLLOVER_DATE = "last_automatic_rollover_date"
        private const val KEY_START_VIEW = "start_view_mode"
        // Tasks toggled away from the default collapse state, one set per view; the view name is
        // appended (e.g. "subtask_collapse_overrides_DAILY").
        private const val KEY_SUBTASK_COLLAPSE_OVERRIDES_PREFIX = "subtask_collapse_overrides_"
        // Older builds shared one set across all views; it is discarded on startup.
        private const val KEY_LEGACY_COLLAPSED_SUBTASK_TASK_IDS = "collapsed_subtask_task_ids"
        private const val KEY_SUBTASKS_COLLAPSED_BY_DEFAULT = "subtasks_collapsed_by_default"
        private const val KEY_HIDE_COMPLETED_IN_LIST = "hide_completed_in_list"
        private const val KEY_COLLAPSIBLE_DAYS_IN_LIST = "collapsible_days_in_list"
        // ISO dates of the List view days the user collapsed.
        private const val KEY_COLLAPSED_LIST_DAYS = "collapsed_list_days"

        // Views that show tasks with collapsible subtasks; each keeps its own collapse state.
        val SUBTASK_COLLAPSE_VIEWS = listOf(ViewMode.DAILY, ViewMode.MONTHLY, ViewMode.LIST)
    }

    private val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun putBoolean(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    // --- Appearance ---

    private val _isDarkMode = MutableStateFlow(prefs?.getBoolean(KEY_IS_DARK_MODE, true) ?: true)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun setDarkMode(enabled: Boolean) {
        _isDarkMode.value = enabled
        putBoolean(KEY_IS_DARK_MODE, enabled)
    }

    private val _useDynamicColors =
        MutableStateFlow(prefs?.getBoolean(KEY_USE_DYNAMIC_COLORS, false) ?: false)
    val useDynamicColors: StateFlow<Boolean> = _useDynamicColors.asStateFlow()

    fun setUseDynamicColors(enabled: Boolean) {
        _useDynamicColors.value = enabled
        putBoolean(KEY_USE_DYNAMIC_COLORS, enabled)
    }

    // --- Which views and widgets are shown ---

    private fun readShowPreference(showKey: String, legacyHideKey: String): Boolean {
        val p = prefs ?: return true
        return when {
            p.contains(showKey) -> p.getBoolean(showKey, true)
            p.contains(legacyHideKey) -> !p.getBoolean(legacyHideKey, false)
            else -> true
        }
    }

    private val _showMonthlyTaskList = MutableStateFlow(
        readShowPreference(KEY_SHOW_MONTHLY_TASK_LIST, KEY_LEGACY_HIDE_MONTHLY_TASK_LIST)
    )
    val showMonthlyTaskList: StateFlow<Boolean> = _showMonthlyTaskList.asStateFlow()

    fun setShowMonthlyTaskList(show: Boolean) {
        _showMonthlyTaskList.value = show
        putBoolean(KEY_SHOW_MONTHLY_TASK_LIST, show)
    }

    private val _showMonthlyView = MutableStateFlow(
        readShowPreference(KEY_SHOW_MONTHLY_VIEW, KEY_LEGACY_HIDE_MONTHLY_VIEW)
    )
    val showMonthlyView: StateFlow<Boolean> = _showMonthlyView.asStateFlow()

    fun setShowMonthlyView(show: Boolean) {
        _showMonthlyView.value = show
        putBoolean(KEY_SHOW_MONTHLY_VIEW, show)
    }

    private val _showListView = MutableStateFlow(
        readShowPreference(KEY_SHOW_LIST_VIEW, KEY_LEGACY_HIDE_LIST_VIEW)
    )
    val showListView: StateFlow<Boolean> = _showListView.asStateFlow()

    fun setShowListView(show: Boolean) {
        _showListView.value = show
        putBoolean(KEY_SHOW_LIST_VIEW, show)
    }

    private val _showDailyProgress =
        MutableStateFlow(prefs?.getBoolean(KEY_SHOW_DAILY_PROGRESS, true) ?: true)
    val showDailyProgress: StateFlow<Boolean> = _showDailyProgress.asStateFlow()

    fun setShowDailyProgress(show: Boolean) {
        _showDailyProgress.value = show
        putBoolean(KEY_SHOW_DAILY_PROGRESS, show)
    }

    private val _startViewMode = MutableStateFlow(
        prefs?.getString(KEY_START_VIEW, ViewMode.DAILY.name)?.let { name ->
            try {
                val mode = ViewMode.valueOf(name)
                if (mode == ViewMode.SETTINGS) ViewMode.DAILY else mode
            } catch (_: Exception) {
                ViewMode.DAILY
            }
        } ?: ViewMode.DAILY
    )
    val startViewMode: StateFlow<ViewMode> = _startViewMode.asStateFlow()

    fun setStartViewMode(mode: ViewMode) {
        val validMode = if (mode == ViewMode.SETTINGS) ViewMode.DAILY else mode
        _startViewMode.value = validMode
        prefs?.edit()?.putString(KEY_START_VIEW, validMode.name)?.apply()
    }

    // --- Rollover ---

    private val _rolloverCleanupEnabled =
        MutableStateFlow(prefs?.getBoolean(KEY_ROLLOVER_CLEANUP_ENABLED, true) ?: true)
    val rolloverCleanupEnabled: StateFlow<Boolean> = _rolloverCleanupEnabled.asStateFlow()

    fun setRolloverCleanupEnabled(enabled: Boolean) {
        _rolloverCleanupEnabled.value = enabled
        putBoolean(KEY_ROLLOVER_CLEANUP_ENABLED, enabled)
    }

    /** The day the throttled automatic rollover last ran, so it runs at most once a day. */
    var lastAutomaticRolloverDate: String? = prefs?.getString(KEY_LAST_AUTOMATIC_ROLLOVER_DATE, null)
        private set

    fun recordAutomaticRollover(dateIso: String) {
        lastAutomaticRolloverDate = dateIso
        prefs?.edit()?.putString(KEY_LAST_AUTOMATIC_ROLLOVER_DATE, dateIso)?.apply()
    }

    /** Lifts the once-per-day throttle (used after the whole data set was replaced by a restore). */
    fun clearAutomaticRolloverThrottle() {
        lastAutomaticRolloverDate = null
        prefs?.edit()?.remove(KEY_LAST_AUTOMATIC_ROLLOVER_DATE)?.apply()
    }

    private val _lastRolloverInfo = MutableStateFlow<Pair<String, String>?>(
        prefs?.getString(KEY_LAST_ROLLOVER_DATE, null)?.let { date ->
            Pair(date, prefs.getString(KEY_LAST_ROLLOVER_TIMESTAMP, "") ?: "")
        }
    )
    val lastRolloverInfo: StateFlow<Pair<String, String>?> = _lastRolloverInfo.asStateFlow()

    fun recordRollover(dateIso: String, time: String) {
        prefs?.edit()
            ?.putString(KEY_LAST_ROLLOVER_DATE, dateIso)
            ?.putString(KEY_LAST_ROLLOVER_TIMESTAMP, time)
            ?.apply()
        _lastRolloverInfo.value = Pair(dateIso, time)
    }

    // --- List view ---

    // Whether the List view drops tasks and subtasks as soon as they are completed.
    private val _hideCompletedInList =
        MutableStateFlow(prefs?.getBoolean(KEY_HIDE_COMPLETED_IN_LIST, false) ?: false)
    val hideCompletedInList: StateFlow<Boolean> = _hideCompletedInList.asStateFlow()

    fun setHideCompletedInList(hide: Boolean) {
        _hideCompletedInList.value = hide
        putBoolean(KEY_HIDE_COMPLETED_IN_LIST, hide)
    }

    // Whether tapping a day header in the List view collapses that day.
    private val _collapsibleDaysInList =
        MutableStateFlow(prefs?.getBoolean(KEY_COLLAPSIBLE_DAYS_IN_LIST, false) ?: false)
    val collapsibleDaysInList: StateFlow<Boolean> = _collapsibleDaysInList.asStateFlow()

    fun setCollapsibleDaysInList(enabled: Boolean) {
        if (_collapsibleDaysInList.value == enabled) return
        _collapsibleDaysInList.value = enabled
        putBoolean(KEY_COLLAPSIBLE_DAYS_IN_LIST, enabled)
        // Turning the option off drops collapsed days so re-enabling starts fully expanded.
        if (!enabled) setCollapsedListDays(emptySet())
    }

    // The List view days currently collapsed. Days before today are dropped on load so stale
    // entries do not accumulate, and the pruned set is persisted.
    private val _collapsedListDays = MutableStateFlow(loadCollapsedListDays())
    val collapsedListDays: StateFlow<Set<AppDate>> = _collapsedListDays.asStateFlow()

    private fun loadCollapsedListDays(): Set<AppDate> {
        val stored = prefs?.getStringSet(KEY_COLLAPSED_LIST_DAYS, emptySet()).orEmpty()
        val today = AppDate.today()
        val upcoming = stored
            .mapNotNull { runCatching { AppDate.parseIso(it) }.getOrNull() }
            .filter { it >= today }
            .toSet()
        if (upcoming.size != stored.size) persistCollapsedListDays(upcoming)
        return upcoming
    }

    fun toggleListDayCollapsed(date: AppDate) {
        if (!_collapsibleDaysInList.value) return
        val current = _collapsedListDays.value
        setCollapsedListDays(if (date in current) current - date else current + date)
    }

    private fun setCollapsedListDays(days: Set<AppDate>) {
        if (_collapsedListDays.value == days) return
        _collapsedListDays.value = days
        persistCollapsedListDays(days)
    }

    private fun persistCollapsedListDays(days: Set<AppDate>) {
        prefs?.edit()
            ?.putStringSet(KEY_COLLAPSED_LIST_DAYS, days.map { it.toIsoString() }.toSet())
            ?.apply()
    }

    // --- Subtask collapse state ---

    // Whether tasks show their subtasks collapsed (Daily, Monthly and List views) unless toggled.
    private val _subtasksCollapsedByDefault =
        MutableStateFlow(prefs?.getBoolean(KEY_SUBTASKS_COLLAPSED_BY_DEFAULT, false) ?: false)
    val subtasksCollapsedByDefault: StateFlow<Boolean> = _subtasksCollapsedByDefault.asStateFlow()

    fun setSubtasksCollapsedByDefault(collapsed: Boolean) {
        if (_subtasksCollapsedByDefault.value == collapsed) return
        _subtasksCollapsedByDefault.value = collapsed
        putBoolean(KEY_SUBTASKS_COLLAPSED_BY_DEFAULT, collapsed)
        // Changing the default applies to every task in every view, so earlier toggles are dropped.
        clearAllSubtaskCollapseOverrides()
    }

    // Per view, the tasks the user toggled away from the default, keyed by task id so every
    // occurrence of a recurring task shares the same state within that view. A task is collapsed
    // in a view when it is in that view's set XOR subtasks are collapsed by default.
    private val _subtaskCollapseOverrides = MutableStateFlow(
        SUBTASK_COLLAPSE_VIEWS.associateWith { view ->
            prefs?.getStringSet(subtaskCollapseOverridesKey(view), emptySet())
                ?.mapNotNull { it.toLongOrNull() }
                ?.toSet()
                ?: emptySet()
        }
    )
    val subtaskCollapseOverrides: StateFlow<Map<ViewMode, Set<Long>>> =
        _subtaskCollapseOverrides.asStateFlow()

    init {
        prefs?.takeIf { it.contains(KEY_LEGACY_COLLAPSED_SUBTASK_TASK_IDS) }
            ?.edit()?.remove(KEY_LEGACY_COLLAPSED_SUBTASK_TASK_IDS)?.apply()
    }

    fun isSubtasksCollapsed(view: ViewMode, taskId: Long): Boolean =
        (taskId in overridesFor(view)) != _subtasksCollapsedByDefault.value

    fun toggleSubtasksCollapsed(view: ViewMode, taskId: Long) {
        val current = overridesFor(view)
        setSubtaskCollapseOverrides(view, if (taskId in current) current - taskId else current + taskId)
    }

    fun expandSubtasks(view: ViewMode, taskId: Long) {
        if (isSubtasksCollapsed(view, taskId)) toggleSubtasksCollapsed(view, taskId)
    }

    fun clearSubtaskCollapseOverride(taskId: Long) {
        SUBTASK_COLLAPSE_VIEWS.forEach { view ->
            val current = overridesFor(view)
            if (taskId in current) setSubtaskCollapseOverrides(view, current - taskId)
        }
    }

    private fun clearAllSubtaskCollapseOverrides() {
        SUBTASK_COLLAPSE_VIEWS.forEach { setSubtaskCollapseOverrides(it, emptySet()) }
    }

    private fun overridesFor(view: ViewMode): Set<Long> =
        _subtaskCollapseOverrides.value[view].orEmpty()

    private fun subtaskCollapseOverridesKey(view: ViewMode) =
        KEY_SUBTASK_COLLAPSE_OVERRIDES_PREFIX + view.name

    private fun setSubtaskCollapseOverrides(view: ViewMode, ids: Set<Long>) {
        require(view in SUBTASK_COLLAPSE_VIEWS) { "$view has no collapsible subtasks" }
        _subtaskCollapseOverrides.value = _subtaskCollapseOverrides.value + (view to ids)
        prefs?.edit()
            ?.putStringSet(subtaskCollapseOverridesKey(view), ids.map { it.toString() }.toSet())
            ?.apply()
    }
}
