package com.example.pulsemeter

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var previewSurface: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var cameraRateText: TextView
    private lateinit var manualRateText: TextView
    private lateinit var startButton: Button
    private lateinit var flashButton: Button
    private lateinit var manualButton: Button
    private lateinit var compareButton: Button

    private var camera: Camera? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Убедитесь, что это ваш layout

        // Инициализация элементов UI
        previewSurface = findViewById(R.id.previewSurface)
        statusText = findViewById(R.id.statusText)
        cameraRateText = findViewById(R.id.cameraRateText)
        manualRateText = findViewById(R.id.manualRateText)
        startButton = findViewById(R.id.startButton)
        flashButton = findViewById(R.id.flashButton)
        manualButton = findViewById(R.id.manualButton)
        compareButton = findViewById(R.id.compareButton)

        // Установка слушателя для кнопки "Начать измерение"
        startButton.setOnClickListener {
            startPulseMeasurement()
        }

        // Установка слушателя для кнопки "Включить фонарь"
        flashButton.setOnClickListener {
            toggleFlashlight()
        }

        // Установка слушателя для кнопки "Нажать при пульсе"
        manualButton.setOnClickListener {
            manualPulseRecording()
        }

        // Установка слушателя для кнопки "Сравнить результаты"
        compareButton.setOnClickListener {
            compareResults()
        }

        // Включение камеры
        initCamera()
    }

    private fun initCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 200)
            return
        }
        try {
            camera = Camera.open()
            val holder: SurfaceHolder = previewSurface.holder
            holder.addCallback(object : SurfaceHolder.Callback {
            
                override fun surfaceCreated(holder: SurfaceHolder) {
                    camera?.setPreviewDisplay(holder)
                    camera?.startPreview()
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    camera?.stopPreview()
                    camera?.release()
                }
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Camera is not available.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startPulseMeasurement() {
        // Реализуйте логику считывания пульса
        statusText.text = "Измеряется пульс..."
        // Здесь, возможно, будет вызов метода считывания пульса с определенной логикой
    }

    private fun toggleFlashlight() {
        // Реализуйте логику управления фонариком
        // Здесь добавьте логику для включения и выключения фонарика
        Toast.makeText(this, "Фонарик включен/выключен", Toast.LENGTH_SHORT).show()
    }

    private fun manualPulseRecording() {
        // Логика для ручного считывания пульса
        // Обновите `manualRateText` с вручную записанным значением пульса
        manualRateText.text = "Пульс зафиксирован"
    }

    private fun compareResults() {
        // Логика для сравнения результатов
        // Например, сравните cameraRateText и manualRateText
        Toast.makeText(this, "Сравнение результатов", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == 200) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initCamera()
            } else {
                Toast.makeText(this, "Камера недоступна", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
