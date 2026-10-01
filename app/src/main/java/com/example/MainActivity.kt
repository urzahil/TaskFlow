package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.db.AppDatabase
import com.example.data.drive.GoogleDriveBackupManager
import com.example.data.repository.TaskRepository
import com.example.ui.MainScreen
import com.example.ui.TaskViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: TaskViewModel by viewModels {
        val database = AppDatabase.getInstance(applicationContext)
        val repository = TaskRepository(database.taskDao())
        val driveBackupManager = GoogleDriveBackupManager(applicationContext, repository)
        TaskViewModel.Factory(repository, driveBackupManager, applicationContext)
    }

    private var dateChangeReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
            val useDynamicColors by viewModel.useDynamicColors.collectAsStateWithLifecycle()
            MyApplicationTheme(darkTheme = isDarkMode, dynamicColor = useDynamicColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Check for new day and trigger rollover every time the app enters foreground
        viewModel.onAppForegrounded()

        // Register receiver for system date or timezone change while app is active
        if (dateChangeReceiver == null) {
            dateChangeReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    viewModel.onDateChanged()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.registerReceiver(
                        this,
                        dateChangeReceiver,
                        filter,
                        ContextCompat.RECEIVER_EXPORTED
                    )
                } else {
                    registerReceiver(dateChangeReceiver, filter)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onStop() {
        super.onStop()
        dateChangeReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
            dateChangeReceiver = null
        }
    }
}
