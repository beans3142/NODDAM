package com.temperature.monitor.app

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.content.Intent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter

class MainActivity : AppCompatActivity() {
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var temperatureInput: EditText
    private lateinit var statusText: TextView
    private lateinit var currentTemperatureText: TextView
    private lateinit var stopAlarmButton: Button
    private var isMonitoring = false
    private var currentTemperature = 0.0
    private var alarmCooldown = false
    
    private val temperatureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "TEMP_UPDATE" -> {
                    val temperature = intent.getDoubleExtra("temperature", 0.0)
                    updateCurrentTemperature(temperature)
                }
                "ALARM_COOLDOWN" -> {
                    alarmCooldown = intent.getBooleanExtra("cooldown", false)
                    updateUI()
                }
            }
        }
    }
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startTemperatureMonitoring()
        } else {
            Toast.makeText(this, "알림 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initializeViews()
        setupClickListeners()
        checkPermissions()
        registerTemperatureReceiver()
    }
    
    private fun initializeViews() {
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        temperatureInput = findViewById(R.id.temperatureInput)
        statusText = findViewById(R.id.statusText)
        currentTemperatureText = findViewById(R.id.currentTemperatureText)
        stopAlarmButton = findViewById(R.id.stopAlarmButton)
        
        // 기본 온도 임계값 설정
        temperatureInput.setText("30.0")
        updateCurrentTemperature(0.0)
    }
    
    private fun setupClickListeners() {
        startButton.setOnClickListener {
            if (!isMonitoring) {
                checkPermissionsAndStart()
            }
        }
        
        stopButton.setOnClickListener {
            if (isMonitoring) {
                stopTemperatureMonitoring()
            }
        }
        
        stopAlarmButton.setOnClickListener {
            stopAlarm()
        }
    }
    
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                requestNotificationPermission()
            }
        }
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }
    
    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                requestNotificationPermission()
                return
            }
        }
        startTemperatureMonitoring()
    }
    
    private fun startTemperatureMonitoring() {
        val temperatureThreshold = temperatureInput.text.toString().toDoubleOrNull()
        if (temperatureThreshold == null) {
            Toast.makeText(this, "올바른 온도를 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = Intent(this, TemperatureMonitorService::class.java).apply {
            action = TemperatureMonitorService.ACTION_START_MONITORING
            putExtra(TemperatureMonitorService.EXTRA_TEMPERATURE_THRESHOLD, temperatureThreshold)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isMonitoring = true
        updateUI()
        Toast.makeText(this, "온도 모니터링이 시작되었습니다", Toast.LENGTH_SHORT).show()
    }
    
    private fun stopTemperatureMonitoring() {
        val intent = Intent(this, TemperatureMonitorService::class.java).apply {
            action = TemperatureMonitorService.ACTION_STOP_MONITORING
        }
        
        startService(intent)
        isMonitoring = false
        updateUI()
        Toast.makeText(this, "온도 모니터링이 중지되었습니다", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateUI() {
        startButton.isEnabled = !isMonitoring
        stopButton.isEnabled = isMonitoring
        temperatureInput.isEnabled = !isMonitoring
        
        statusText.text = when {
            isMonitoring && alarmCooldown -> "온도 모니터링 중... (알람 쿨다운)"
            isMonitoring -> "온도 모니터링 중..."
            else -> "모니터링 중지됨"
        }
        
        // 알람 중지 버튼 상태 업데이트
        stopAlarmButton.isEnabled = isMonitoring && alarmCooldown
    }
    
    private fun stopAlarm() {
        val intent = Intent(this, TemperatureMonitorService::class.java).apply {
            action = "STOP_ALARM"
        }
        startService(intent)
        Toast.makeText(this, "알람이 중지되었습니다", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateCurrentTemperature(temperature: Double) {
        currentTemperature = temperature
        currentTemperatureText.text = "현재 온도: ${String.format("%.1f", temperature)}°C"
    }
    
    private fun registerTemperatureReceiver() {
        val filter = IntentFilter().apply {
            addAction("TEMP_UPDATE")
            addAction("ALARM_COOLDOWN")
        }
        registerReceiver(temperatureReceiver, filter)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(temperatureReceiver)
    }
    
    override fun onResume() {
        super.onResume()
        updateUI()
    }
}