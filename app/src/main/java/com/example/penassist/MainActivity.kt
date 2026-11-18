package com.example.penassist

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    private fun dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(28), dpi(36), dpi(28), dpi(28))
        }
        col.addView(TextView(this).apply {
            text = "✒ Pen Assist"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            textSize = 16f
            setPadding(0, dpi(12), 0, dpi(12))
            text = """ปุ่มลอยแบบ Air Command สำหรับ Honor Magic-Pencil
                |
                |• เขียนบนหน้าจอ (Screen Write)
                |• เลือกและครอบตัด (Smart Select)
                |• โน้ตด่วน
                |• ค้นหา/แปลภาพหน้าจอด้วย Google Lens
                |
                |ตั้งค่าครั้งแรก:
                |1) กดปุ่มด้านล่างเพื่อไปหน้า "การช่วยการเข้าถึง" แล้วเปิด "Pen Assist – ปุ่มลอยปากกา"
                |   (ถ้าเปิดไม่ได้/เป็นสีเทา: ไปที่ ข้อมูลแอป > เมนู ⋮ > อนุญาตการตั้งค่าที่ถูกจำกัด)
                |2) ตั้งค่าแบตเตอรี่ของแอปเป็น "จัดการด้วยตนเอง" และอนุญาตให้ทำงานเบื้องหลัง เพื่อไม่ให้ระบบปิดปุ่มลอย""".trimMargin()
        })
        status = TextView(this).apply {
            textSize = 17f
            setPadding(0, dpi(8), 0, dpi(16))
        }
        col.addView(status)

        fun button(label: String, onClick: () -> Unit) {
            col.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { onClick() }
            })
        }
        button("1) เปิดหน้าการช่วยการเข้าถึง (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("2) ข้อมูลแอป / แบตเตอรี่") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button("แสดงปุ่มลอย") {
            val svc = PenAssistService.instance
            if (svc != null) svc.showBubble()
            else Toast.makeText(this, "กรุณาเปิดบริการใน Accessibility ก่อน", Toast.LENGTH_LONG).show()
        }
        button("📝 เปิดโน้ตด่วน") {
            startActivity(Intent(this, EditorActivity::class.java)
                .putExtra(EditorActivity.EXTRA_MODE, EditorActivity.MODE_NOTE))
        }

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        if (PenAssistService.instance != null) {
            status.text = "✅ บริการทำงานอยู่ – แตะปุ่มลอย ✒ เพื่อเปิดเมนู"
            status.setTextColor(0xFF2E7D32.toInt())
        } else {
            status.text = "⚠️ ยังไม่ได้เปิดบริการ Pen Assist"
            status.setTextColor(Color.RED)
        }
    }
}
