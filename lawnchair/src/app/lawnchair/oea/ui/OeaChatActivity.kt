package app.lawnchair.oea.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.lawnchair.oea.agent.OeaAgent
import app.lawnchair.oea.engine.OeaEngine

/**
 * OEA command surface. It intentionally uses the existing Android view stack so the
 * launcher does not gain another UI framework dependency just for the command layer.
 */
class OeaChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val agent = OeaAgent.get(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val title = TextView(this).apply {
            text = "OEA"
            textSize = 28f
        }
        val command = EditText(this).apply {
            hint = "Try: open Settings"
            singleLine = true
        }
        val result = TextView(this)
        val run = Button(this).apply {
            text = "Run"
            setOnClickListener {
                result.text = when (val response = agent.handle(command.text.toString())) {
                    is OeaEngine.Result.Success -> response.message
                    is OeaEngine.Result.Failure -> response.message
                }
            }
        }

        root.addView(title, match())
        root.addView(command, match())
        root.addView(run, match())
        root.addView(result, match())
        setContentView(root)
    }

    private fun match(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = 16 }
}
