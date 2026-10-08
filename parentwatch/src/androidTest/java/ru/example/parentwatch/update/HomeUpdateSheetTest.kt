package ru.example.parentwatch.update

import android.app.Dialog
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R

/** Exercises the real home listeners without downloading or installing an APK. */
@RunWith(AndroidJUnit4::class)
class HomeUpdateSheetTest {
    @Test fun updateSheetCanOpenCloseAndReopenWhileHomeRelayouts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        instrumentation.waitForIdleSync()
        lateinit var notices: LinearLayout
        lateinit var marker: TextView
        try {
            instrumentation.runOnMainSync {
                notices = activity.findViewById(R.id.updateNoticeContainer)
                marker = TextView(activity).apply { text = "Update sheet regression check" }
                notices.addView(marker)
                activity.findViewById<View>(R.id.homeContent).viewTreeObserver.dispatchOnGlobalLayout()
            }
            repeat(3) {
                instrumentation.runOnMainSync {
                    val button = activity.findViewById<View>(R.id.homeUpdateButton)
                    assertEquals(View.VISIBLE, button.visibility)
                    button.performClick()
                    // The notice now belongs to the dialog, so Activity.findViewById cannot find it.
                    assertNull(activity.findViewById<View>(R.id.updateNoticeContainer))
                    activity.findViewById<View>(R.id.homeContent).viewTreeObserver.dispatchOnGlobalLayout()
                    val sheetField = MainActivity::class.java.getDeclaredField("homeSheet").apply {
                        isAccessible = true
                    }
                    val sheet = sheetField.get(activity) as Dialog
                    assertTrue(sheet.isShowing)
                    sheet.dismiss()
                }
                instrumentation.waitForIdleSync()
                // Dialog delivers onDismiss through the main queue; wait before checking restoration.
                instrumentation.runOnMainSync {
                    assertSame(notices, activity.findViewById<View>(R.id.updateNoticeContainer))
                    activity.findViewById<View>(R.id.homeContent).viewTreeObserver.dispatchOnGlobalLayout()
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                runCatching { notices.removeView(marker) }
                activity.finish()
            }
        }
    }
}
