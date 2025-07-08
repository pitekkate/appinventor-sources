package edu.mit.appinventor.aicompanion3

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import edu.mit.appinventor.aicompanion3.viewmodels.ConnectionViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: ConnectionViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_modern)
        
        viewModel = ConnectionViewModel(application)
        
        val connectButton = findViewById<MaterialButton>(R.id.connectButton)
        connectButton.setOnClickListener {
            viewModel.connectToAppInventor()
        }
    }
}
