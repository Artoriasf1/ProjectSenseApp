package com.example.myapp // Замените на ваше имя пакета

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {

    private var count = 0 // Счетчик нажатий кнопки

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Обработчик нажатий кнопки
        countButton.setOnClickListener {
            count++ // Увеличиваем счетчик
            countTextView.text = "Нажато: $count" // Обновляем текст
        }
    }
}
