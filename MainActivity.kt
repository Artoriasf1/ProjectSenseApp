package com.example.myapplication

import android.content.Context
import android.os.*
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private val pulseRecords = mutableListOf<Long>()
    private var lastClickTime: Long = 0
    private lateinit var vibrator: Vibrator
    private val resetHandler = Handler(Looper.getMainLooper())
    private val resetRunnable = Runnable { resetMeasurement() }

    // UI элементы
    private lateinit var pulseButton: Button
    private lateinit var resultText: TextView
    private lateinit var pulseDisplay: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        initViews()
        initVibrator()
        setupButtonClickListener()
        setupWindowInsets()
    }

    private fun initViews() {
        pulseButton = findViewById(R.id.pulseButton)
        resultText = findViewById(R.id.resultText)
        pulseDisplay = findViewById(R.id.pulseDisplay)
    }

    private fun initVibrator() {
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private fun setupButtonClickListener() {
        pulseButton.setOnClickListener {
            handlePulseButtonClick()
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun handlePulseButtonClick() {
        try {
            vibrateOnClick()
            val currentTime = SystemClock.elapsedRealtime()

            if (lastClickTime != 0L) {
                recordPulseInterval(currentTime)
            }

            lastClickTime = currentTime
            resetAutoResetTimer()
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorToast(e)
        }
    }

    private fun vibrateOnClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    50,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }

    private fun recordPulseInterval(currentTime: Long) {
        val interval = currentTime - lastClickTime
        pulseRecords.add(interval)

        pulseDisplay.text = "Записано ${pulseRecords.size} ударов"

        if (pulseRecords.size >= 5) {
            calculatePulse()
        }
    }

    private fun resetAutoResetTimer() {
        resetHandler.removeCallbacks(resetRunnable)
        resetHandler.postDelayed(resetRunnable, 2000)
    }

    private fun calculatePulse() {
        try {
            if (pulseRecords.size < 5) {
                resultText.text = "Нужно минимум 5 нажатий"
                return
            }

            val averageInterval = pulseRecords.average()
            val bpm = (60000 / averageInterval).toInt()
            val diagram = buildPulseDiagram(bpm)

            resultText.text = diagram
            resetMeasurement()
        } catch (e: Exception) {
            e.printStackTrace()
            resultText.text = "Ошибка расчета пульса"
        }
    }

    private fun buildPulseDiagram(bpm: Int): String {
        return buildString {
            append("Ваш пульс: $bpm BPM\n")
            append("Диаграмма:\n")

            pulseRecords.forEachIndexed { index, interval ->
                val beats = (interval / 100).toInt()
                append("${index + 1}: ${"♥".repeat(beats.coerceAtMost(10))}\n")
            }
        }
    }

    private fun resetMeasurement() {
        pulseRecords.clear()
        lastClickTime = 0
        pulseDisplay.text = "Нажмите кнопку в ритме пульса"
        resetHandler.removeCallbacks(resetRunnable)
    }

    private fun showErrorToast(e: Exception) {
        runOnUiThread {
            Toast.makeText(
                this@MainActivity,
                "Ошибка: ${e.localizedMessage}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
