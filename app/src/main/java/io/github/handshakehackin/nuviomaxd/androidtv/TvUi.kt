package io.github.handshakehackin.nuviomaxd.androidtv

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal fun Activity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

internal fun Activity.tvScreen(title: String): LinearLayout {
    val scroll = ScrollView(this).apply { isFillViewport = true }
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(48), dp(28), dp(48), dp(28))
    }
    scroll.addView(body)
    setContentView(scroll)
    body.addView(TextView(this).apply {
        text = title
        textSize = 36f
        setTextColor(Color.WHITE)
        setPadding(0, 0, 0, dp(16))
    })
    return body
}

internal fun Activity.tvText(body: LinearLayout, value: String): TextView = TextView(this).also {
    it.text = value
    it.textSize = 18f
    it.setTextColor(Color.rgb(203, 211, 229))
    it.setPadding(0, 0, 0, dp(20))
    body.addView(it)
}

internal fun Activity.tvButton(body: LinearLayout, label: String, action: (View) -> Unit): Button =
    Button(this, null, 0, R.style.NuvioButton).also {
        it.text = label
        it.isFocusable = true
        it.isFocusableInTouchMode = false
        it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68)).apply {
            bottomMargin = dp(12)
        }
        it.setOnClickListener(action)
        body.addView(it)
    }
