import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.utils.ColorTemplate
import kotlinx.android.synthetic.main.activity_main.*
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private val entries = ArrayList<Entry>() // Хранит точки графика
    private lateinit var lineDataSet: LineDataSet
    private lateinit var lineData: LineData
    private var lastXValue = 0f // Последняя координата X

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupChart() // Настройка графика

        // Обработка нажатия кнопки
        buttonPulse.setOnClickListener {
            simulateHeartbeat() // Добавляем "удар сердца" на график
        }
    }

    private fun setupChart() {
        lineDataSet = LineDataSet(entries, "Пульс")
        lineDataSet.color = ColorTemplate.MATERIAL_COLORS[0]
        lineDataSet.setDrawCircles(false) // Убираем точки (для плавности)
        lineDataSet.lineWidth = 3f

        lineData = LineData(lineDataSet)
        lineChart.data = lineData
        lineChart.description.text = "График сердцебиения"
        lineChart.animateX(1000) // Анимация
    }

    private fun simulateHeartbeat() {
        // Имитация удара сердца (резкий подъем и спад)
        val baseY = Random.nextFloat() * 2 + 2f // Случайная базовая линия
        val peakY = baseY + 5f // Пик удара

        // Добавляем точки для "удара"
        entries.add(Entry(lastXValue, baseY))
        entries.add(Entry(lastXValue + 0.5f, peakY))
        entries.add(Entry(lastXValue + 1f, baseY))
        lastXValue += 1.5f

        // Обновляем график
        lineDataSet.notifyDataSetChanged()
        lineData.notifyDataChanged()
        lineChart.notifyDataSetChanged()
        lineChart.moveViewToX(lastXValue) // Автоматическая прокрутка
    }
}
