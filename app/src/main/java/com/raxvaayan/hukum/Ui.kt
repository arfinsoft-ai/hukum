package com.raxvaayan.hukum

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object C {
    val BG = Color.parseColor("#0E0F13")
    val CARD = Color.parseColor("#1A1C23")
    val LINE = Color.parseColor("#2A2D36")
    val GOLD = Color.parseColor("#F5B83D")
    val TEXT = Color.parseColor("#ECEDF0")
    val MUTED = Color.parseColor("#8B8F9A")
    val USER = Color.parseColor("#3A2F14")
    val RED = Color.parseColor("#E5484D")
    val GREEN = Color.parseColor("#30A46C")
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun round(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

fun Context.text(s: String, size: Float = 15f, color: Int = C.TEXT, bold: Boolean = false) = TextView(this).apply {
    text = s; textSize = size; setTextColor(color)
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
}

fun Context.btn(s: String, bg: Int = C.GOLD, fg: Int = Color.BLACK, onClick: (View) -> Unit) = Button(this).apply {
    text = s; isAllCaps = false; textSize = 14f; setTextColor(fg)
    background = round(bg, dp(10).toFloat())
    setPadding(dp(14), 0, dp(14), 0)
    minHeight = dp(44); minimumHeight = dp(44); stateListAnimator = null
    setOnClickListener(onClick)
}

fun Context.vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
