package app.lawnchair.oea.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lawnchair.oea.agent.OeaAgent
import app.lawnchair.oea.engine.OeaEngine

class OeaChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val agent = OeaAgent.get(this)

        setContent {
            var command by remember { mutableStateOf("") }
            var result by remember { mutableStateOf<String?>(null) }

            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("OEA", style = MaterialTheme.typography.headlineMedium)
                    Text("Deterministic command surface")
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Command") },
                        singleLine = true,
                    )
                    Button(onClick = {
                        result = when (val response = agent.handle(command)) {
                            is OeaEngine.Result.Success -> response.message
                            is OeaEngine.Result.Failure -> response.message
                        }
                    }) {
                        Text("Run")
                    }
                    result?.let { Text(it) }
                }
            }
        }
    }
}
