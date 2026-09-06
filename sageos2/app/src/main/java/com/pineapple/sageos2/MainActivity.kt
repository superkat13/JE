package com.pineapple.sageos2

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.pineapple.sageos2.core.SageEvent
import com.pineapple.sageos2.core.SageTurnCoordinator

class MainActivity : Activity() {
    private val coordinator = SageTurnCoordinator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val title = TextView(this).apply {
            text = "SageOS 2.0"
            textSize = 28f
        }
        val status = TextView(this).apply {
            textSize = 18f
        }

        root.addView(title)
        root.addView(status)
        setContentView(root)

        coordinator.handle(SageEvent.Start)
        status.text = "Core runtime: ${coordinator.snapshot().state}\nOne Sage. One turn coordinator."
    }
}
