package com.example.projectsenseapp
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.example.projectsenseapp.R

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Находим наши view элементы
        val helloButton: Button = findViewById(R.id.helloButton)
        val helloText: TextView = findViewById(R.id.helloText)

        // Устанавливаем обработчик нажатия на кнопку
        helloButton.setOnClickListener {
            // Показываем текст и устанавливаем "Hello World!"
            helloText.visibility = View.VISIBLE
            helloText.text = "Hello World!"
        }
    }
}