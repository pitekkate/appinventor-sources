package edu.mit.appinventor.aicompanion3.viewmodels

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import edu.mit.appinventor.aicompanion3.repository.ConnectionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ConnectionRepository(application)
    val connectionStatus = MutableLiveData<String>("DISCONNECTED")
    val projectList = MutableLiveData<List<String>>()

    fun connectToAppInventor() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                connectionStatus.postValue("CONNECTING")
                repository.connect()
                connectionStatus.postValue("CONNECTED")
                loadProjects()
            } catch (e: Exception) {
                connectionStatus.postValue("ERROR: ${e.message}")
            }
        }
    }

    fun checkConnectionStatus() {
        CoroutineScope(Dispatchers.IO).launch {
            val status = repository.checkConnection()
            connectionStatus.postValue(status)
            if (status == "CONNECTED") loadProjects()
        }
    }

    private fun loadProjects() {
        CoroutineScope(Dispatchers.IO).launch {
            val projects = repository.getAvailableProjects()
            projectList.postValue(projects)
        }
    }

    fun startQrScanner(context: Context) {
        // Implementasi QR scanner
    }
}
