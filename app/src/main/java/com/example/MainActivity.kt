package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.data.db.AppDatabase
import com.example.data.drive.GoogleDriveBackupManager
import com.example.data.repository.TaskRepository
import com.example.ui.MainScreen
import com.example.ui.TaskViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.util.AppIconManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: TaskViewModel by viewModels {
        val database = AppDatabase.getInstance(applicationContext)
        val repository = TaskRepository(database.taskDao())
        val driveBackupManager = GoogleDriveBackupManager(applicationContext, repository)
        TaskViewModel.Factory(repository, driveBackupManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Sync dynamic launcher icon with current open tasks count for today
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.todayOpenTasksCount.collect { count ->
                    AppIconManager.updateLauncherIcon(applicationContext, count)
                }
            }
        }

        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Ensure launcher icon is up-to-date as user returns to home screen
        AppIconManager.updateLauncherIcon(applicationContext, viewModel.todayOpenTasksCount.value)
    }
}
