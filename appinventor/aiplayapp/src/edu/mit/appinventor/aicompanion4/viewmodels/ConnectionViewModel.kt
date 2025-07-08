package edu.mit.appinventor.aicompanion3.viewmodels

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import edu.mit.appinventor.aicompanion3.repository.ConnectionRepository

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ConnectionRepository()
    val connectionStatus = MutableLiveData<String>()
    val projects = MutableLiveData<List<Project>>()

    fun connectToAppInventor() {
        repository.connect { status ->
            connectionStatus.postValue(status)
        }
    }

    fun loadProjects() {
        repository.getProjects { projectList ->
            projects.postValue(projectList)
        }
    }
}
