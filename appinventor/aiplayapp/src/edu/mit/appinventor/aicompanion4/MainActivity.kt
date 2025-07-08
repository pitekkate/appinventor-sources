package edu.mit.appinventor.aicompanion3

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import edu.mit.appinventor.aicompanion3.databinding.ActivityModernMainBinding
import edu.mit.appinventor.aicompanion3.viewmodels.MainViewModel

class ModernMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModernMainBinding
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Inisialisasi View Binding
        binding = ActivityModernMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Setup ViewModel
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        
        // Setup UI
        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        binding.connectButton.setOnClickListener {
            viewModel.connectToAppInventor()
        }
        
        binding.qrScannerButton.setOnClickListener {
            viewModel.startQrScanner(this)
        }
        
        binding.settingsButton.setOnClickListener {
            // Navigasi ke settings
        }
    }

    private fun observeViewModel() {
        viewModel.connectionStatus.observe(this) { status ->
            binding.connectionStatus.text = status
            when (status) {
                "CONNECTED" -> binding.statusIcon.setImageResource(R.drawable.ic_connected)
                "CONNECTING" -> binding.statusIcon.setImageResource(R.drawable.ic_connecting)
                else -> binding.statusIcon.setImageResource(R.drawable.ic_disconnected)
            }
        }
        
        viewModel.projectList.observe(this) { projects ->
            // Update project list
        }
    }
    
    override fun onResume() {
        super.onResume()
        viewModel.checkConnectionStatus()
    }
}
