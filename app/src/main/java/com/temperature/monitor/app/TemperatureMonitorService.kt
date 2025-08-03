package com.temperature.monitor.app

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.*
import android.media.MediaPlayer
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class TemperatureMonitorService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var isMonitoring = false
    private var temperatureThreshold = 30.0 // 기본 임계값 30도
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var sensorManager: SensorManager? = null
    private var temperatureSensor: Sensor? = null
    private var currentTemperature = 25.0 // 기본 온도
    private var alarmCooldown = false // 알람 쿨다운 상태
    private var alarmCooldownJob: Job? = null // 알람 쿨다운 작업
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "TemperatureMonitor"
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        const val ACTION_STOP_ALARM = "STOP_ALARM"
        const val EXTRA_TEMPERATURE_THRESHOLD = "TEMPERATURE_THRESHOLD"
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        initializeAlarmComponents()
        initializeTemperatureSensor()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                temperatureThreshold = intent.getDoubleExtra(EXTRA_TEMPERATURE_THRESHOLD, 30.0)
                startMonitoring()
            }
            ACTION_STOP_MONITORING -> {
                stopMonitoring()
            }
            ACTION_STOP_ALARM -> {
                stopAlarm()
            }
        }
        return START_STICKY
    }
    
    private fun startMonitoring() {
        if (isMonitoring) return
        
        isMonitoring = true
        startForeground(NOTIFICATION_ID, createNotification("온도 모니터링 중..."))
        
        // 알람 쿨다운 초기화
        resetAlarmCooldown()
        
        // 센서 초기화 및 등록
        initializeTemperatureSensor()
        val sensor = temperatureSensor
        if (sensor != null) {
            sensorManager?.registerListener(
                sensorEventListener,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            android.util.Log.d("TemperatureMonitor", "센서 등록 완료: ${sensor.name}")
        } else {
            android.util.Log.d("TemperatureMonitor", "등록할 센서가 없습니다")
        }
        
        serviceScope.launch {
            // 센서 등록 후 잠시 대기 (첫 번째 센서 이벤트를 기다림)
            delay(2000)
            
            // 첫 번째 온도 측정
            val initialTemperature = getCurrentTemperature()
            updateNotification("현재 온도: ${initialTemperature}°C")
            sendTemperatureUpdate(initialTemperature)
            
            while (isMonitoring) {
                delay(5000) // 5초 대기
                
                if (!isMonitoring) break
                
                val currentTemp = getCurrentTemperature()
                updateNotification("현재 온도: ${currentTemp}°C")
                
                // MainActivity로 현재 온도 전송
                sendTemperatureUpdate(currentTemp)
                
                if (currentTemp > temperatureThreshold && !alarmCooldown) {
                    triggerAlarm(currentTemp)
                }
            }
        }
    }
    
    private fun stopMonitoring() {
        isMonitoring = false
        
        // 알람 쿨다운 초기화
        resetAlarmCooldown()
        
        // 센서 해제
        sensorManager?.unregisterListener(sensorEventListener)
        
        stopForeground(true)
        stopSelf()
    }
    
    private fun initializeTemperatureSensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        // 사용 가능한 모든 센서 확인
        val availableSensors = sensorManager?.getSensorList(Sensor.TYPE_ALL)
        android.util.Log.d("TemperatureMonitor", "사용 가능한 센서 수: ${availableSensors?.size}")
        
        // 온도 센서 찾기 (여러 타입 시도)
        temperatureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        
        if (temperatureSensor == null) {
            temperatureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_TEMPERATURE)
        }
        
        if (temperatureSensor == null) {
            // 모든 센서에서 온도 관련 센서 찾기
            availableSensors?.forEach { sensor ->
                if (sensor.name.contains("temperature", ignoreCase = true) || 
                    sensor.name.contains("temp", ignoreCase = true)) {
                    temperatureSensor = sensor
                    android.util.Log.d("TemperatureMonitor", "온도 관련 센서 발견: ${sensor.name}")
                    return@forEach
                }
            }
        }
        
        // 센서 상태 로그
        val sensor = temperatureSensor
        if (sensor != null) {
            android.util.Log.d("TemperatureMonitor", "온도 센서 초기화 성공: ${sensor.name}")
        } else {
            android.util.Log.d("TemperatureMonitor", "온도 센서를 찾을 수 없습니다. 기본값 사용")
        }
    }
    
    private fun getCurrentTemperature(): Double {
        val result = if (temperatureSensor != null && currentTemperature > 0) {
            // 실제 센서 값 사용 (센서가 있고 유효한 값이 있을 때)
            currentTemperature
        } else {
            // 센서가 없거나 유효하지 않은 경우 안정적인 기본값
            25.0
        }
        
        android.util.Log.d("TemperatureMonitor", "getCurrentTemperature() 호출 - 센서: ${temperatureSensor?.name}, 현재값: $currentTemperature, 반환값: $result")
        return result
    }
    
    private fun triggerAlarm(temperature: Double) {
        // 알람 소리 재생
        playAlarmSound()
        
        // 알림 표시
        showTemperatureAlert(temperature)
    }
    
    private fun initializeAlarmComponents() {
        // 진동 초기화
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        
        // MediaPlayer 초기화
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            isLooping = true // 반복 재생
        }
    }
    
    private fun playAlarmSound() {
        try {
            // 오디오 매니저를 통해 볼륨을 최대로 설정
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
            
            // 시스템 알람 소리 재생
            mediaPlayer?.apply {
                if (!isPlaying) {
                    setDataSource(this@TemperatureMonitorService, android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI)
                    prepare()
                    start()
                }
            }
            
            // 진동 시작
            startVibration()
            
        } catch (e: Exception) {
            // 시스템 알람 소리가 없을 경우 기본 알람 소리 사용
            try {
                mediaPlayer?.apply {
                    if (!isPlaying) {
                        setDataSource(this@TemperatureMonitorService, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
                        prepare()
                        start()
                    }
                }
            } catch (ex: Exception) {
                // 모든 방법이 실패할 경우 진동만 실행
                startVibration()
            }
        }
    }
    
    private fun startVibration() {
        vibrator?.let { vibrator ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000) // 진동 패턴
                val vibrationEffect = VibrationEffect.createWaveform(vibrationPattern, 0)
                vibrator.vibrate(vibrationEffect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000), 0)
            }
        }
    }
    
    private fun stopAlarm() {
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
                reset()
            }
        }
        vibrator?.cancel()
        
        // 10분 쿨다운 시작
        startAlarmCooldown()
    }
    
    private fun startAlarmCooldown() {
        alarmCooldown = true
        alarmCooldownJob?.cancel() // 기존 쿨다운 취소
        
        // MainActivity로 쿨다운 상태 전송
        sendCooldownUpdate(true)
        
        alarmCooldownJob = serviceScope.launch {
            delay(10 * 60 * 1000) // 10분 대기
            alarmCooldown = false
            sendCooldownUpdate(false)
        }
        
        // 쿨다운 시작 알림
        updateNotification("알람 중지됨 - 10분간 알람 비활성화")
    }
    
    private fun resetAlarmCooldown() {
        alarmCooldown = false
        alarmCooldownJob?.cancel()
        alarmCooldownJob = null
        sendCooldownUpdate(false)
    }
    
    private fun showTemperatureAlert(temperature: Double) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        val alertNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("온도 경고!")
            .setContentText("온도가 ${temperature}°C로 임계값을 초과했습니다!")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(1002, alertNotification)
    }
    
    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("온도 모니터")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }
    
    private fun sendTemperatureUpdate(temperature: Double) {
        val intent = Intent("TEMP_UPDATE").apply {
            putExtra("temperature", temperature)
        }
        sendBroadcast(intent)
    }
    
    private fun sendCooldownUpdate(cooldown: Boolean) {
        val intent = Intent("ALARM_COOLDOWN").apply {
            putExtra("cooldown", cooldown)
        }
        sendBroadcast(intent)
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "온도 모니터",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "온도 모니터링 알림"
        }
        
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TemperatureMonitor::WakeLock"
        ).apply {
            acquire(10*60*1000L) // 10분
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
        serviceScope.cancel()
        wakeLock?.release()
        stopAlarm()
        mediaPlayer?.release()
        sensorManager?.unregisterListener(sensorEventListener)
        alarmCooldownJob?.cancel()
    }
    
    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                android.util.Log.d("TemperatureMonitor", "센서 이벤트 수신: ${it.sensor.name} (타입: ${it.sensor.type})")
                
                // 온도 센서 타입 체크 (더 포괄적으로)
                if (it.sensor.type == Sensor.TYPE_AMBIENT_TEMPERATURE || 
                    it.sensor.type == Sensor.TYPE_TEMPERATURE ||
                    it.sensor.name.contains("temperature", ignoreCase = true) ||
                    it.sensor.name.contains("temp", ignoreCase = true)) {
                    
                    val newTemp = it.values[0].toDouble()
                    android.util.Log.d("TemperatureMonitor", "원시 온도 값: $newTemp°C")
                    
                    // 유효한 온도 범위 체크 (-50도 ~ 100도)
                    if (newTemp >= -50 && newTemp <= 100) {
                        currentTemperature = newTemp
                        android.util.Log.d("TemperatureMonitor", "온도 업데이트 완료: ${currentTemperature}°C (센서: ${it.sensor.name})")
                    } else {
                        android.util.Log.d("TemperatureMonitor", "유효하지 않은 온도 값: $newTemp°C")
                    }
                } else {
                    android.util.Log.d("TemperatureMonitor", "온도 센서가 아님: ${it.sensor.name}")
                }
            }
        }
        
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            android.util.Log.d("TemperatureMonitor", "센서 정확도 변경: ${sensor?.name} - $accuracy")
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
} 