package com.familysafety.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.familysafety.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Some permissions denied — limited functionality.", Toast.LENGTH_LONG).show()
        }
        startSafetyService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(FamilySafetyService.PREFS_NAME, Context.MODE_PRIVATE)
        binding.etServerUrl.setText(prefs.getString(FamilySafetyService.KEY_SERVER_URL, "") ?: "")
        binding.etChildName.setText(prefs.getString(FamilySafetyService.KEY_CHILD_NAME, "") ?: "")

        setupButtons()
        refreshStatusUi()
    }

    override fun onResume() {
        super.onResume()
        refreshStatusUi()
    }

    private fun setupButtons() {
        binding.btnSaveUrl.setOnClickListener {
            val url = binding.etServerUrl.text?.toString()?.trim() ?: ""
            if (url.isEmpty()) {
                binding.tilServerUrl.error = "Please enter a server URL"
                return@setOnClickListener
            }
            binding.tilServerUrl.error = null
            val childName = binding.etChildName.text?.toString()?.trim() ?: ""
            hideKeyboard()

            val prefs = getSharedPreferences(FamilySafetyService.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(FamilySafetyService.KEY_SERVER_URL, url)
                .putString(FamilySafetyService.KEY_CHILD_NAME, childName)
                .apply()

            Toast.makeText(this, "Settings saved. Starting service…", Toast.LENGTH_SHORT).show()
            checkPermissionsAndStart()
            hideFromLauncher()
        }

        binding.btnStartService.setOnClickListener { checkPermissionsAndStart() }
        binding.btnStopService.setOnClickListener { stopSafetyService() }
    }

    private fun checkPermissionsAndStart() {
        val required = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startSafetyService()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startSafetyService() {
        val url = binding.etServerUrl.text?.toString()?.trim() ?: ""
        val intent = Intent(this, FamilySafetyService::class.java).apply {
            putExtra(FamilySafetyService.EXTRA_SERVER_URL, url)
        }
        ContextCompat.startForegroundService(this, intent)
        refreshStatusUi(running = true)
        Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
    }

    private fun stopSafetyService() {
        stopService(Intent(this, FamilySafetyService::class.java))
        refreshStatusUi(running = false)
        Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
    }

    private fun hideFromLauncher() {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun refreshStatusUi(running: Boolean = isServiceRunning()) {
        if (running) {
            binding.tvServiceStatus.text = getString(R.string.status_running)
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            binding.statusDot.background = ContextCompat.getDrawable(this, R.drawable.shape_status_dot_green)
        } else {
            binding.tvServiceStatus.text = getString(R.string.status_stopped)
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
            binding.statusDot.background = ContextCompat.getDrawable(this, R.drawable.shape_status_dot)
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == FamilySafetyService::class.java.name
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }
}
