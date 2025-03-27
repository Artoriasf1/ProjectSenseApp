package com.example.projectsenseapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaScannerConnection
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.projectsenseapp.databinding.ActivityMainBinding
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@SuppressLint("ClickableViewAccessibility", "Deprecated")
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private val TAG = "HeartRateMonitor"
    private val REQUEST_CAMERA_PERMISSION = 100
    private val REQUEST_STORAGE_PERMISSION = 101
    
    private var camera: Camera? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var processing = AtomicBoolean(false)
    private var flashEnabled = false
    
    private lateinit var binding: ActivityMainBinding
    
    // Данные для обработки
    private val redChannelValues = mutableListOf<Double>()
    private val greenChannelValues = mutableListOf<Double>() 
    private val blueChannelValues = mutableListOf<Double>()
    private val timeValues = mutableListOf<Long>()
    private var startTime: Long = 0
    
    // Улучшенные параметры обработки
    private val FRAMES_TO_PROCESS = 350 // Увеличено для более точного анализа
    private val MOVING_WINDOW_SIZE = 20 // Уменьшено для лучшего обнаружения пиков
    private val REALTIME_UPDATE_WINDOW = 120 // Увеличено окно анализа
    private val REALTIME_UPDATE_INTERVAL = 500L // Интервал обновления в мс
    private val MIN_VALID_HEART_RATE = 40
    private val MAX_VALID_HEART_RATE = 200
    
    // Новые параметры для фильтрации
    private val LOW_CUTOFF_FREQ = 0.5 // Частота нижнего среза в Гц (30 уд/мин)
    private val HIGH_CUTOFF_FREQ = 3.3 // Частота верхнего среза в Гц (200 уд/мин)
    private val QUALITY_THRESHOLD = 0.25 // Порог для определения качества сигнала
    
    // Скользящие средние для быстрой оценки качества сигнала
    private val signalQualityWindow = 20
    private val recentVariances = mutableListOf<Double>()
    private var signalQuality = 0.0 // От 0 до 1
    
    // Для ручного измерения пульса
    private val manualPulseTimestamps = mutableListOf<Long>()
    private val manualHeartRateHandler = Handler(Looper.getMainLooper())
    private val manualHeartRateRunnable = object : Runnable {
        override fun run() {
            // Удаляем старые метки (старше 15 секунд)
            val currentTime = System.currentTimeMillis()
            while (manualPulseTimestamps.isNotEmpty() && currentTime - manualPulseTimestamps[0] > 15000) {
                manualPulseTimestamps.removeAt(0)
            }
            
            // Обновляем отображение пульса, если есть достаточно данных
            if (manualPulseTimestamps.size >= 2) {
                updateManualHeartRate()
            }
            
            // Продолжаем обновление каждую секунду
            manualHeartRateHandler.postDelayed(this, 1000)
        }
    }
    
    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (processing.get() && redChannelValues.size > MOVING_WINDOW_SIZE * 2) {
                updateRealtimeHeartRate()
                updateHandler.postDelayed(this, REALTIME_UPDATE_INTERVAL)
            }
        }
    }
    
    // Режим непрерывного измерения
    private var continuousMode = true
    
    // Добавляем переменную для управления записью данных
    private var isRecordingData = false
    private var recordedData = StringBuilder()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Добавляем обработчик длительного нажатия для показа диагностики
        binding.statusText.setOnLongClickListener {
            showSignalQualityDiagnostics()
            true
        }
        
        // Добавляем обработчик долгого нажатия на кнопку измерения
        binding.startButton.setOnLongClickListener {
            toggleDataRecording()
            true
        }
        
        // Настройка SurfaceView
        surfaceHolder = binding.previewSurface.holder
        surfaceHolder?.addCallback(this)
        
        binding.startButton.setOnClickListener {
            if (processing.get()) {
                stopHeartRateReading()
            } else {
                checkCameraPermissionAndStart()
            }
        }
        
        // Добавляем кнопку для включения/выключения вспышки
        binding.flashButton.setOnClickListener {
            toggleFlash()
        }
        
        // Кнопка для ручного измерения пульса
        binding.manualPulseButton.setOnClickListener {
            recordManualPulse()
        }
        
        // Запускаем обработчик для ручного измерения пульса
        manualHeartRateHandler.post(manualHeartRateRunnable)
    }
    
    private fun toggleFlash() {
        if (camera != null) {
            try {
                val parameters = camera?.parameters
                if (flashEnabled) {
                    parameters?.flashMode = Camera.Parameters.FLASH_MODE_OFF
                    binding.flashButton.text = "Включить подсветку"
                } else {
                    parameters?.flashMode = Camera.Parameters.FLASH_MODE_TORCH
                    binding.flashButton.text = "Выключить подсветку"
                }
                camera?.parameters = parameters
                flashEnabled = !flashEnabled
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при управлении вспышкой: ${e.message}")
                Toast.makeText(this, "Ошибка при управлении вспышкой", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        } else {
            startHeartRateReading()
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startHeartRateReading()
            } else {
                Toast.makeText(
                    this,
                    "Разрешение на использование камеры необходимо для работы приложения",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleDataRecording()
            } else {
                Toast.makeText(
                    this,
                    "Разрешение на хранение данных необходимо для записи отладочной информации",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    private fun startHeartRateReading() {
        if (processing.compareAndSet(false, true)) {
            // Очищаем данные от предыдущего измерения
            redChannelValues.clear()
            greenChannelValues.clear()
            blueChannelValues.clear()
            timeValues.clear()
            startTime = System.currentTimeMillis()
            
            binding.startButton.text = "Остановить"
            binding.statusText.text = "Измерение..."
            binding.pulseRateText.text = "-- уд/мин"
            
            try {
                camera = Camera.open()
                val parameters = camera?.parameters
                
                // Устанавливаем минимальное разрешение для ускорения обработки
                val smallestSize = parameters?.supportedPreviewSizes?.minByOrNull { 
                    it.width * it.height 
                }
                smallestSize?.let {
                    parameters?.setPreviewSize(it.width, it.height)
                }
                
                // Устанавливаем формат изображения и частоту кадров
                parameters?.previewFormat = ImageFormat.NV21
                
                // Максимальная частота кадров для лучшей точности
                val fpsRanges = parameters?.supportedPreviewFpsRange
                fpsRanges?.maxByOrNull { it[Camera.Parameters.PREVIEW_FPS_MAX_INDEX] }?.let { 
                    parameters?.setPreviewFpsRange(it[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                                               it[Camera.Parameters.PREVIEW_FPS_MAX_INDEX])
                }
                
                // Автоматически включаем вспышку при начале измерения
                if (hasFlash()) {
                    parameters?.flashMode = Camera.Parameters.FLASH_MODE_TORCH
                    flashEnabled = true
                    binding.flashButton.text = "Выключить подсветку"
                }
                
                camera?.parameters = parameters
                
                // Настраиваем обратный вызов для обработки кадров
                camera?.setPreviewCallback { data, _ ->
                    processImageData(data)
                }
                
                // Запускаем предварительный просмотр
                camera?.setPreviewDisplay(surfaceHolder)
                camera?.startPreview()
                
                // Запускаем обновление пульса в реальном времени
                updateHandler.postDelayed(updateRunnable, REALTIME_UPDATE_INTERVAL)
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка запуска камеры: ${e.message}")
                stopHeartRateReading()
                Toast.makeText(this, "Ошибка запуска камеры", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun hasFlash(): Boolean {
        return packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
    }
    
    private fun stopHeartRateReading() {
        if (processing.compareAndSet(true, false)) {
            // Останавливаем обновление пульса в реальном времени
            updateHandler.removeCallbacks(updateRunnable)
            
            binding.startButton.text = "Начать измерение"
            binding.statusText.text = "Приложите палец к камере"
            
            // Выключаем светодиод при остановке измерения
            if (flashEnabled) {
                try {
                    val parameters = camera?.parameters
                    parameters?.flashMode = Camera.Parameters.FLASH_MODE_OFF
                    camera?.parameters = parameters
                    flashEnabled = false
                    binding.flashButton.text = "Включить подсветку"
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка при выключении вспышки: ${e.message}")
                }
            }
            
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
            camera = null
            
            // Если достаточно данных, вычисляем пульс
            if (timeValues.size > MOVING_WINDOW_SIZE) {
                calculateHeartRate()
            }
        }
    }
    
    private fun processImageData(data: ByteArray) {
        if (!processing.get()) return
        
        val width = camera?.parameters?.previewSize?.width ?: return
        val height = camera?.parameters?.previewSize?.height ?: return
        
        // Анализируем центральную область изображения
        val centerX = width / 2
        val centerY = height / 2
        val windowSize = minOf(width, height) / 4
        
        var redSum = 0.0
        var greenSum = 0.0
        var blueSum = 0.0
        var sampleCount = 0
        
        // Добавляем оценку стандартного отклонения для определения текстуры
        var redSquareSum = 0.0
        
        // Извлекаем U и V значения (цветовые компоненты)
        val uvOffset = width * height
        
        for (y in centerY - windowSize until centerY + windowSize) {
            for (x in centerX - windowSize until centerX + windowSize) {
                if (y >= 0 && y < height && x >= 0 && x < width) {
                    val yIndex = y * width + x
                    
                    if (yIndex < data.size) {
                        val yValue = data[yIndex].toInt() and 0xFF
                        
                        val uvIndex = uvOffset + (y / 2) * width + (x and 1.inv())
                        
                        if (uvIndex + 1 < data.size) {
                            val uValue = (data[uvIndex].toInt() and 0xFF) - 128
                            val vValue = (data[uvIndex + 1].toInt() and 0xFF) - 128
                            
                            val r = yValue + 1.370705 * vValue
                            val g = yValue - 0.337633 * uValue - 0.698001 * vValue
                            val b = yValue + 1.732446 * uValue
                            
                            val rClamped = maxOf(0.0, minOf(255.0, r))
                            
                            redSum += rClamped
                            greenSum += maxOf(0.0, minOf(255.0, g))
                            blueSum += maxOf(0.0, minOf(255.0, b))
                            redSquareSum += rClamped * rClamped
                            
                            sampleCount++
                        } else {
                            redSum += yValue.toDouble()
                            greenSum += yValue.toDouble() * 0.7
                            blueSum += yValue.toDouble() * 0.5
                            redSquareSum += yValue.toDouble() * yValue.toDouble()
                            sampleCount++
                        }
                    }
                }
            }
        }
        
        if (sampleCount > 0) {
            val avgRed = redSum / sampleCount
            val avgGreen = greenSum / sampleCount
            val avgBlue = blueSum / sampleCount
            
            // Вычисляем стандартное отклонение для красного канала (показатель текстуры)
            val redVariance = (redSquareSum / sampleCount) - (avgRed * avgRed)
            val redStdDev = sqrt(maxOf(0.0, redVariance))
            
            val currentTime = System.currentTimeMillis() - startTime
            
            // Логирование значений RGB каждые 50 кадров
            if (redChannelValues.size % 50 == 0) {
                Log.d(TAG, "RGB значения: R=${avgRed.toInt()}, G=${avgGreen.toInt()}, B=${avgBlue.toInt()}, StdDev=${redStdDev.toInt()}")
            }
            
            // Записываем данные, если включена запись
            if (isRecordingData) {
                recordedData.append("$currentTime,${avgRed.toInt()},${avgGreen.toInt()},${avgBlue.toInt()},${(signalQuality * 100).toInt()}\n")
            }
            
            redChannelValues.add(avgRed)
            greenChannelValues.add(avgGreen)
            blueChannelValues.add(avgBlue)
            timeValues.add(currentTime)
            
            updateSignalQuality()
            
            // В непрерывном режиме не останавливаем сбор данных автоматически
            if (redChannelValues.size >= FRAMES_TO_PROCESS && !continuousMode) {
                runOnUiThread { stopHeartRateReading() }
            } else {
                // Если собрали достаточно данных для анализа, начинаем удалять старые данные
                if (redChannelValues.size > FRAMES_TO_PROCESS) {
                    redChannelValues.removeAt(0)
                    greenChannelValues.removeAt(0)
                    blueChannelValues.removeAt(0)
                    timeValues.removeAt(0)
                }
                
                val progress = if (redChannelValues.size >= FRAMES_TO_PROCESS) 
                    100 
                else 
                    (redChannelValues.size * 100) / FRAMES_TO_PROCESS
                
                val qualityPercent = (signalQuality * 100).toInt()
                runOnUiThread {
                    binding.statusText.text = "Измерение: $progress% (Качество: $qualityPercent%)"
                    
                    if (signalQuality < 0.4 && redChannelValues.size > 30) {
                        // Предупреждение о низком качестве сигнала
                        Toast.makeText(this, "Пожалуйста, прижмите палец плотнее к камере", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    
    /**
     * Оценка качества сигнала на основе вариации последних значений
     */
    private fun updateSignalQuality() {
        if (redChannelValues.size < signalQualityWindow + 1) return
        
        val recentRed = redChannelValues.takeLast(signalQualityWindow)
        val mean = recentRed.average()
        
        // Вычисляем дисперсию
        val variance = recentRed.map { (it - mean).pow(2) }.average()
        
        // Нормализованная дисперсия как показатель качества
        val normalizedVariance = minOf(1.0, variance / 100.0)
        
        recentVariances.add(normalizedVariance)
        if (recentVariances.size > 5) recentVariances.removeAt(0)
        
        // Хорошее качество сигнала должно иметь заметную, но не слишком большую вариацию
        // Слишком маленькая вариация может означать отсутствие контакта с пальцем
        // Слишком большая может означать движение или плохой контакт
        val avgVariance = recentVariances.average()
        
        // Качество сигнала зависит от дисперсии в определенном диапазоне
        signalQuality = when {
            avgVariance < 0.05 -> 0.1 // Слишком стабильный сигнал - нет пульса
            avgVariance < 0.3 -> avgVariance / 0.3 // Хороший диапазон
            avgVariance < 0.8 -> 1.0 - (avgVariance - 0.3) / 0.5 // Слишком большая вариация
            else -> 0.2 // Сильные помехи
        }
    }
    
    /**
     * Функция для обновления значения пульса в реальном времени с улучшенными алгоритмами
     */
    private fun updateRealtimeHeartRate() {
        // Берем только последние данные для анализа в реальном времени
        val dataSize = redChannelValues.size
        if (dataSize < REALTIME_UPDATE_WINDOW) return
        
        val startIndex = maxOf(0, dataSize - REALTIME_UPDATE_WINDOW)
        
        // Используем все три канала с весами для повышения точности
        // Зеленый канал обычно дает лучший результат для пульса
        val combinedValues = mutableListOf<Double>()
        for (i in startIndex until dataSize) {
            val weightedValue = redChannelValues[i] * 0.3 + 
                                greenChannelValues[i] * 0.6 + 
                                blueChannelValues[i] * 0.1
            combinedValues.add(weightedValue)
        }
        
        val realtimeTimeValues = timeValues.subList(startIndex, dataSize)
        
        // Логирование частоты кадров
        if (realtimeTimeValues.size > 2) {
            val avgFrameTime = (realtimeTimeValues.last() - realtimeTimeValues.first()) / 
                              (realtimeTimeValues.size - 1)
            val fps = 1000.0 / avgFrameTime
            Log.d(TAG, "Частота кадров: ${fps.toInt()} FPS, временное окно: ${realtimeTimeValues.size} кадров")
        }
        
        // Применяем полосовой фильтр для удаления шума и выделения полезного сигнала
        val filteredValues = bandpassFilter(combinedValues, realtimeTimeValues)
        
        if (filteredValues.size < 10) return // Слишком мало данных для анализа
        
        // Пытаемся определить пульс несколькими методами для повышения точности
        val bpmFromPeaks = getHeartRateFromPeaks(filteredValues, realtimeTimeValues)
        val bpmFromFFT = getHeartRateFromFFT(filteredValues, realtimeTimeValues)
        
        Log.d(TAG, "Определение пульса: пики=${bpmFromPeaks}, FFT=${bpmFromFFT}, качество=${signalQuality}")
        
        // Взвешенное среднее от двух методов, дающее преимущество более надежному методу
        // на основе текущего качества сигнала
        val finalHeartRate = when {
            bpmFromPeaks == 0 -> bpmFromFFT
            bpmFromFFT == 0 -> bpmFromPeaks
            else -> {
                // Если оба метода дали результаты, берем среднее с поправкой на качество сигнала
                val peakWeight = if (signalQuality > 0.7) 0.7 else 0.3
                val fftWeight = 1.0 - peakWeight
                (bpmFromPeaks * peakWeight + bpmFromFFT * fftWeight).toInt()
            }
        }
        
        if (finalHeartRate > 0) {
            Log.d(TAG, "Итоговый пульс: $finalHeartRate уд/мин")
            runOnUiThread {
                binding.pulseRateText.text = "$finalHeartRate уд/мин"
                // В непрерывном режиме выводим сообщение о непрерывном измерении
                val msg = if (redChannelValues.size >= FRAMES_TO_PROCESS) 
                    "Непрерывное измерение" 
                else 
                    "Измерение: ${(redChannelValues.size * 100) / FRAMES_TO_PROCESS}%"
                binding.statusText.text = msg
            }
        }
        
        // Продолжаем обновлять в непрерывном режиме
        if (processing.get()) {
            updateHandler.postDelayed(updateRunnable, REALTIME_UPDATE_INTERVAL)
        }
    }
    
    /**
     * Полосовой фильтр для выделения частот характерных для сердцебиения (0.5-3.3Гц)
     */
    private fun bandpassFilter(values: List<Double>, timeValues: List<Long>): List<Double> {
        if (values.size < 4) return values
        
        val result = mutableListOf<Double>()
        val mean = values.average()
        
        // Нормализация входных данных
        val normalizedValues = values.map { it - mean }
        
        // Простой IIR-фильтр второго порядка
        // Коэффициенты зависят от частоты дискретизации и частотного диапазона
        val a = 0.95 // Коэффициенты фильтра
        
        var y1 = 0.0
        var y2 = 0.0
        
        for (i in normalizedValues.indices) {
            // Приближенная частота дискретизации
            val samplingRate = if (i > 0) (1000.0 / (timeValues[i] - timeValues[i-1])) else 30.0
            
            // Адаптивная фильтрация с учетом частоты дискретизации
            val lowCutoff = minOf(2.0 * LOW_CUTOFF_FREQ / samplingRate, 0.4)
            val highCutoff = minOf(2.0 * HIGH_CUTOFF_FREQ / samplingRate, 0.9)
            
            // Полосовой фильтр
            val xt = normalizedValues[i]
            var yt = xt
            
            // Удаление низких частот (high-pass)
            yt = a * (yt + y1 - y2)
            y2 = y1
            y1 = yt
            
            result.add(yt)
        }
        
        return result
    }
    
    /**
     * Получение частоты пульса по пикам в сигнале
     */
    private fun getHeartRateFromPeaks(values: List<Double>, timeValues: List<Long>): Int {
        // Находим пики с улучшенным алгоритмом
        val peaks = mutableListOf<Int>()
        val minPeakDistance = 15 // Минимальное расстояние между пиками в сэмплах
        
        // Динамический порог для обнаружения пиков
        var threshold = 0.0
        if (values.size > 10) {
            // Используем определенный процентиль амплитуды для адаптивного порога
            val sortedAmplitudes = values.map { abs(it) }.sorted()
            threshold = sortedAmplitudes[(sortedAmplitudes.size * 0.7).toInt()] * 0.5
        }
        
        var lastPeakIndex = -minPeakDistance
        
        for (i in 2 until values.size - 2) {
            // Условие пика с адаптивным порогом
            if (values[i] > threshold && 
                values[i] > values[i - 1] && values[i] > values[i - 2] &&
                values[i] > values[i + 1] && values[i] > values[i + 2] &&
                i - lastPeakIndex >= minPeakDistance) {
                
                peaks.add(i)
                lastPeakIndex = i
            }
        }
        
        Log.d(TAG, "Анализ пиков: найдено ${peaks.size} пиков, порог=${"%.2f".format(threshold)}")
        
        // Вычисляем средний интервал между пиками
        if (peaks.size >= 3) {
            val intervals = mutableListOf<Long>()
            
            for (i in 1 until peaks.size) {
                if (peaks[i] < timeValues.size && peaks[i-1] < timeValues.size) {
                    val interval = timeValues[peaks[i]] - timeValues[peaks[i-1]]
                    intervals.add(interval)
                }
            }
            
            // Медианная фильтрация интервалов для устойчивости к выбросам
            if (intervals.size >= 3) {
                intervals.sort()
                val medianInterval = intervals[intervals.size / 2]
                Log.d(TAG, "Медианный интервал между пиками: $medianInterval мс")
                
                // Проверка на разумные пределы
                if (medianInterval > 0) {
                    val bpm = (60.0 * 1000.0 / medianInterval).toInt()
                    if (bpm in MIN_VALID_HEART_RATE..MAX_VALID_HEART_RATE) {
                        return bpm
                    } else {
                        Log.d(TAG, "Значение пульса вне допустимого диапазона: $bpm уд/мин")
                    }
                }
            }
        }
        
        return 0 // Не удалось определить
    }
    
    /**
     * Получение частоты пульса с использованием быстрого преобразования Фурье (БПФ)
     */
    private fun getHeartRateFromFFT(values: List<Double>, timeValues: List<Long>): Int {
        if (values.size < 32) return 0 // Нужно минимум 32 точки для БПФ
        
        // Подготавливаем данные для БПФ
        val n = findNextPowerOfTwo(values.size)
        val real = DoubleArray(n) { if (it < values.size) values[it] else 0.0 }
        val imag = DoubleArray(n) { 0.0 }
        
        // Применяем оконную функцию Хэмминга для уменьшения утечки спектра
        applyWindow(real)
        
        // Выполняем БПФ
        fft(real, imag)
        
        // Вычисляем средний интервал времени для определения частоты дискретизации
        var avgDelta = 0.0
        for (i in 1 until timeValues.size) {
            avgDelta += (timeValues[i] - timeValues[i-1])
        }
        avgDelta /= (timeValues.size - 1)
        val samplingRate = 1000.0 / avgDelta // в герцах
        Log.d(TAG, "FFT анализ: частота дискретизации=${samplingRate.toInt()} Гц, размер окна=$n")
        
        // Находим доминирующую частоту в спектре, соответствующую пульсу
        val minFreqIndex = (n * LOW_CUTOFF_FREQ / samplingRate).toInt()
        val maxFreqIndex = minOf((n * HIGH_CUTOFF_FREQ / samplingRate).toInt(), n/2)
        
        var maxMagnitude = 0.0
        var dominantFreqIndex = 0
        
        for (i in minFreqIndex until maxFreqIndex) {
            val magnitude = sqrt(real[i] * real[i] + imag[i] * imag[i])
            if (magnitude > maxMagnitude) {
                maxMagnitude = magnitude
                dominantFreqIndex = i
            }
        }
        
        // Переводим индекс доминирующей частоты в частоту в Гц
        val dominantFreq = dominantFreqIndex * samplingRate / n
        Log.d(TAG, "FFT: доминирующая частота=${"%.2f".format(dominantFreq)} Гц, магнитуда=${"%.2f".format(maxMagnitude)}")
        
        // Переводим частоту в удары в минуту
        val bpm = (dominantFreq * 60.0).toInt()
        
        // Проверяем на допустимый диапазон пульса
        return if (bpm in MIN_VALID_HEART_RATE..MAX_VALID_HEART_RATE) bpm else 0
    }
    
    /**
     * Применяет оконную функцию Хэмминга к данным для БПФ
     */
    private fun applyWindow(data: DoubleArray) {
        for (i in data.indices) {
            // Оконная функция Хэмминга: 0.54 - 0.46 * cos(2πi/(N-1))
            val windowValue = 0.54 - 0.46 * cos(2.0 * Math.PI * i / (data.size - 1))
            data[i] *= windowValue
        }
    }
    
    /**
     * Находит следующую степень двойки, большую или равную n
     */
    private fun findNextPowerOfTwo(n: Int): Int {
        var power = 1
        while (power < n) {
            power *= 2
        }
        return power
    }
    
    /**
     * Реализация алгоритма быстрого преобразования Фурье (БПФ)
     */
    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        
        // Битовая инверсия для упорядочивания
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                // Меняем местами
                val tempReal = real[i]
                val tempImag = imag[i]
                real[i] = real[j]
                imag[i] = imag[j]
                real[j] = tempReal
                imag[j] = tempImag
            }
            
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }
        
        // Вычисление БПФ
        var l2 = 1
        while (l2 < n) {
            val l1 = l2 * 2
            val angle = (-Math.PI / l2)
            val wReal = cos(angle)
            val wImag = sin(angle)
            
            for (j in 0 until n step l1) {
                var wTmpReal = 1.0
                var wTmpImag = 0.0
                
                for (i in 0 until l2) {
                    val iw = i + j
                    val ip = iw + l2
                    
                    val tempReal = wTmpReal * real[ip] - wTmpImag * imag[ip]
                    val tempImag = wTmpReal * imag[ip] + wTmpImag * real[ip]
                    
                    real[ip] = real[iw] - tempReal
                    imag[ip] = imag[iw] - tempImag
                    real[iw] += tempReal
                    imag[iw] += tempImag
                    
                    // Обновляем множитель поворота
                    val nextWReal = wTmpReal * wReal - wTmpImag * wImag
                    val nextWImag = wTmpReal * wImag + wTmpImag * wReal
                    wTmpReal = nextWReal
                    wTmpImag = nextWImag
                }
            }
            
            l2 = l1
        }
    }
    
    private fun calculateHeartRate() {
        // Используем комбинацию всех трех каналов RGB с весами для улучшения точности
        val combinedValues = mutableListOf<Double>()
        
        for (i in 0 until redChannelValues.size) {
            // Зеленый канал обычно дает наилучшие результаты для измерения пульса
            val weightedValue = redChannelValues[i] * 0.3 + 
                                greenChannelValues[i] * 0.6 + 
                                blueChannelValues[i] * 0.1
            combinedValues.add(weightedValue)
        }
        
        // Применяем полосовой фильтр для выделения пульсовой волны
        val filteredValues = bandpassFilter(combinedValues, timeValues)
        
        if (filteredValues.size < MOVING_WINDOW_SIZE * 2) {
            runOnUiThread {
                binding.pulseRateText.text = "Недостаточно данных"
                binding.statusText.text = "Слишком короткое измерение"
                Toast.makeText(this, "Недостаточно данных для определения пульса", Toast.LENGTH_LONG).show()
            }
            return
        }
        
        // Получаем пульс двумя методами
        val bpmFromPeaks = getHeartRateFromPeaks(filteredValues, timeValues)
        val bpmFromFFT = getHeartRateFromFFT(filteredValues, timeValues)
        
        // Финальный результат - взвешенное среднее или наиболее надежное значение
        val finalHeartRate = when {
            bpmFromPeaks == 0 -> bpmFromFFT
            bpmFromFFT == 0 -> bpmFromPeaks
            else -> {
                // Если разница между методами небольшая, берем среднее
                val diff = abs(bpmFromPeaks - bpmFromFFT)
                if (diff < 10) {
                    (bpmFromPeaks * 0.6 + bpmFromFFT * 0.4).toInt()
                } else if (signalQuality > 0.7) {
                    // При хорошем качестве сигнала предпочитаем метод пиков
                    bpmFromPeaks
                } else {
                    // При низком качестве сигнала предпочитаем БПФ
                    bpmFromFFT
                }
            }
        }
        
        runOnUiThread {
            if (finalHeartRate > 0) {
                binding.pulseRateText.text = "$finalHeartRate уд/мин"
                binding.statusText.text = "Измерение завершено"
            } else {
                binding.pulseRateText.text = "Ошибка измерения"
                binding.statusText.text = "Не удалось определить пульс"
                Toast.makeText(this, "Не удалось определить пульс, повторите измерение", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun recordManualPulse() {
        // Записываем текущее время
        val currentTime = System.currentTimeMillis()
        manualPulseTimestamps.add(currentTime)
        
        // Вибрация для обратной связи
        val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
        
        // Обновляем пульс после добавления новой метки
        if (manualPulseTimestamps.size >= 2) {
            updateManualHeartRate()
        } else {
            runOnUiThread {
                binding.statusText.text = "Отмечено 1 нажатие. Нажмите еще раз."
            }
        }
    }
    
    private fun updateManualHeartRate() {
        if (manualPulseTimestamps.size < 2) return
        
        // Вычисляем средний интервал между ударами
        val intervals = mutableListOf<Long>()
        for (i in 1 until manualPulseTimestamps.size) {
            intervals.add(manualPulseTimestamps[i] - manualPulseTimestamps[i-1])
        }
        
        // Используем медиану для устойчивости к выбросам
        intervals.sort()
        val medianInterval = intervals[intervals.size / 2]
        
        // Вычисляем пульс в ударах в минуту
        val bpm = (60.0 * 1000.0 / medianInterval).toInt()
        
        // Проверяем на разумные пределы
        val heartRate = if (bpm in MIN_VALID_HEART_RATE..MAX_VALID_HEART_RATE) bpm else 0
        
        runOnUiThread {
            if (heartRate > 0) {
                binding.pulseRateText.text = "$heartRate уд/мин"
                binding.statusText.text = "Ручное измерение: ${manualPulseTimestamps.size} ударов"
            } else {
                binding.statusText.text = "Нажимайте в ритме пульса. Отмечено: ${manualPulseTimestamps.size}"
            }
        }
    }
    
    /**
     * Показывает диагностическую информацию о качестве сигнала
     */
    private fun showSignalQualityDiagnostics() {
        if (redChannelValues.size < 5) {
            Toast.makeText(this, "Недостаточно данных для диагностики", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Берем последние значения для анализа
        val lastRedValues = redChannelValues.takeLast(5)
        val lastGreenValues = greenChannelValues.takeLast(5)
        val lastBlueValues = blueChannelValues.takeLast(5)
        
        // Вычисляем среднюю яркость для каждого канала
        val avgRed = lastRedValues.average()
        val avgGreen = lastGreenValues.average()
        val avgBlue = lastBlueValues.average()
        
        // Общая яркость
        val brightness = avgRed * 0.5 + avgGreen * 0.3 + avgBlue * 0.2
        
        // Проверка отношения красного канала к другим
        val redToGreenRatio = avgRed / (avgGreen + 1.0)
        val redToBlueRatio = avgRed / (avgBlue + 1.0)
        
        // Вариация в красном канале
        val redVariation = if (lastRedValues.size > 3) {
            lastRedValues.maxOrNull()!! - lastRedValues.minOrNull()!!
        } else {
            0.0
        }
        
        // Логирование подробной диагностической информации
        Log.i(TAG, """
            Диагностика:
            Размер данных: ${redChannelValues.size}
            Яркость: ${"%.1f".format(brightness)}
            Красный: ${"%.1f".format(avgRed)}
            Зеленый: ${"%.1f".format(avgGreen)}
            Синий: ${"%.1f".format(avgBlue)}
            Отношение Кр/Зел: ${"%.2f".format(redToGreenRatio)}
            Отношение Кр/Син: ${"%.2f".format(redToBlueRatio)}
            Вариация красного: ${"%.2f".format(redVariation)}
            Качество сигнала: ${(signalQuality * 100).toInt()}%
        """.trimIndent())
        
        // Результаты диагностики
        val diagnosticInfo = """
            Диагностика качества сигнала:
            
            Яркость: ${"%.1f".format(brightness)}
            Красный: ${"%.1f".format(avgRed)}
            Зеленый: ${"%.1f".format(avgGreen)}
            Синий: ${"%.1f".format(avgBlue)}
            
            Отношение Кр/Зел: ${"%.2f".format(redToGreenRatio)}
            Отношение Кр/Син: ${"%.2f".format(redToBlueRatio)}
            
            Вариация красного: ${"%.2f".format(redVariation)}
            
            Качество сигнала: ${(signalQuality * 100).toInt()}%
        """.trimIndent()
        
        // Показываем диалог с информацией
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Диагностика")
            .setMessage(diagnosticInfo)
            .setPositiveButton("ОК") { dialog, _ -> dialog.dismiss() }
            .show()
    }
    
    // Метод для включения/выключения записи данных для отладки
    private fun toggleDataRecording() {
        // Проверяем разрешения для записи
        if (checkStoragePermissions()) {
            isRecordingData = !isRecordingData
            
            if (isRecordingData) {
                // Начинаем запись данных
                recordedData.clear()
                recordedData.append("time,red,green,blue,quality\n")
                Toast.makeText(this, "Запись данных началась", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Запись данных сигнала началась")
            } else {
                // Останавливаем запись и сохраняем данные
                if (recordedData.isNotEmpty()) {
                    saveRecordedData()
                }
            }
        } else {
            Toast.makeText(this, "Необходимо разрешение для сохранения данных", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Проверка разрешений для работы с файлами
    private fun checkStoragePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Для Android 11+ (API 30+)
            return Environment.isExternalStorageManager()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Для Android 6+ (API 23+)
            val writePermission = ContextCompat.checkSelfPermission(
                this, 
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            
            if (!writePermission) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_STORAGE_PERMISSION
                )
                return false
            }
            return true
        }
        // До Android 6 разрешения предоставлялись при установке
        return true
    }
    
    // Сохранение записанных данных в файл
    private fun saveRecordedData() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "pulse_data_$timestamp.csv"
            
            // Сохраняем во внешнее хранилище в директории Documents
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            
            val file = File(downloadsDir, filename)
            val writer = FileWriter(file)
            writer.append(recordedData.toString())
            writer.flush()
            writer.close()
            
            Log.i(TAG, "Данные сохранены в файл: ${file.absolutePath}")
            Toast.makeText(this, "Данные сохранены в ${file.name}", Toast.LENGTH_LONG).show()
            
            // Делаем файл видимым в галерее
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при сохранении данных: ${e.message}")
            Toast.makeText(this, "Ошибка при сохранении данных", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created")
    }
    
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed")
    }
    
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopHeartRateReading()
    }
    
    override fun onPause() {
        super.onPause()
        stopHeartRateReading()
        manualHeartRateHandler.removeCallbacks(manualHeartRateRunnable)
        
        // Сохраняем данные, если запись была активна
        if (isRecordingData && recordedData.isNotEmpty()) {
            saveRecordedData()
            isRecordingData = false
        }
    }
    
    override fun onResume() {
        super.onResume()
        manualHeartRateHandler.post(manualHeartRateRunnable)
    }
}
