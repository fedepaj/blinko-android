package com.federicopaglioni.blinko

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch

/** Small form builder for the Lab and Settings tabs (iOS Form look: section headers, rows, sliders, switches). */
class Form(val ctx: Context, val root: LinearLayout) {
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun header(t: String): TextView = TextView(ctx).apply { text = t.uppercase(); textSize = 12f; setTextColor(Color.rgb(156, 223, 156)); setPadding(0, dp(18), 0, dp(6)); root.addView(this) }
    fun label(t: String): TextView = TextView(ctx).apply { text = t; textSize = 15f; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, 0); root.addView(this) }
    fun note(t: String): TextView = TextView(ctx).apply { text = t; textSize = 12f; setTextColor(Color.rgb(158, 158, 158)); setPadding(0, dp(2), 0, dp(4)); root.addView(this) }
    /** "key ........ value" row; returns the value view so it can be refreshed. */
    fun row(name: String, value: String): TextView {
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        line.addView(TextView(ctx).apply { text = name; textSize = 15f; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        val v = TextView(ctx).apply { text = value; textSize = 14f; typeface = Typeface.MONOSPACE; setTextColor(Color.rgb(170, 170, 170)); gravity = Gravity.END }
        line.addView(v); root.addView(line); return v
    }
    fun rowView(name: String, v: View) {
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(TextView(ctx).apply { text = name; textSize = 15f; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        line.addView(v); root.addView(line)
    }
    fun spinner(items: List<String>, selected: Int, onSel: (Int) -> Unit): Spinner = Spinner(ctx).apply {
        adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, items)
        if (items.isNotEmpty()) setSelection(selected.coerceIn(0, items.size - 1), false)
        var first = true
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (first) { first = false; return }; onSel(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
    fun seek(max: Int, value: Int, onChange: (Int) -> Unit): SeekBar = SeekBar(ctx).apply {
        this.max = max; progress = value.coerceIn(0, max)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(this)
    }
    fun switch(t: String, value: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch = MaterialSwitch(ctx).apply {
        text = t; isChecked = value; textSize = 15f; setTextColor(Color.WHITE); setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, v -> onChange(v) }; root.addView(this)
    }
    fun edit(value: String, type: Int, onDone: (String) -> Unit): EditText = EditText(ctx).apply {
        setText(value); inputType = type; minWidth = dp(100); textSize = 14f; gravity = Gravity.END
        setOnFocusChangeListener { _, has -> if (!has) onDone(text.toString()) }
    }
    fun button(t: String, color: Int = Color.rgb(156, 223, 156), onClick: () -> Unit): Button = Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
        text = t; isAllCaps = false; textSize = 13f; setTextColor(color); setOnClickListener { onClick() }
    }
    fun divider() { root.addView(View(ctx).apply { setBackgroundColor(Color.rgb(40, 40, 40)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1) }) }

    companion object { const val TEXT = InputType.TYPE_CLASS_TEXT; const val NUMBER = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
}
