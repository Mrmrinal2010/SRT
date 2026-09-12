package io.github.thibaultbee.streampack.app

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private val prefs: SharedPreferences by lazy { getSharedPreferences("carstream", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etHost = findViewById<TextInputEditText>(R.id.etHost)
        val etPort = findViewById<TextInputEditText>(R.id.etPort)
        val etStreamId = findViewById<TextInputEditText>(R.id.etStreamId)
        val etLatency = findViewById<TextInputEditText>(R.id.etLatency)
        
        val etResolution = findViewById<TextInputEditText>(R.id.etResolution)
        val etBitrate = findViewById<TextInputEditText>(R.id.etBitrate)
        val etFps = findViewById<TextInputEditText>(R.id.etFps)
        
        val btnApply = findViewById<Button>(R.id.btnApplySettings)

        // Load existing
        etHost.setText(prefs.getString("srt_host", "[YOUR-HOME-IPV6]"))
        etPort.setText(prefs.getInt("srt_port", 5000).toString())
        etStreamId.setText(prefs.getString("srt_streamid", "publish:car"))
        etLatency.setText(prefs.getInt("srt_latency", 4000).toString())

        etResolution.setText(prefs.getString("res", "3840x2160"))
        etBitrate.setText(prefs.getInt("bitrate", 20000).toString())
        etFps.setText(prefs.getInt("fps", 30).toString())

        btnApply.setOnClickListener {
            val host = etHost.text.toString().trim()
            val port = etPort.text.toString().trim().toIntOrNull() ?: 5000
            val streamId = etStreamId.text.toString().trim()
            val latency = etLatency.text.toString().trim().toIntOrNull() ?: 4000
            
            val res = etResolution.text.toString().trim().ifEmpty { "3840x2160" }
            val bitrate = etBitrate.text.toString().trim().toIntOrNull() ?: 20000
            val fps = etFps.text.toString().trim().toIntOrNull() ?: 30

            // Save individual
            prefs.edit()
                .putString("srt_host", host)
                .putInt("srt_port", port)
                .putString("srt_streamid", streamId)
                .putInt("srt_latency", latency)
                .putString("res", res)
                .putInt("bitrate", bitrate)
                .putInt("fps", fps)
                .apply()
                
            // Construct and save the URL for MainActivity to use directly
            val url = "srt://$host:$port?streamid=$streamId&latency=$latency&mss=1360"
            prefs.edit().putString("url", url).apply()

            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
