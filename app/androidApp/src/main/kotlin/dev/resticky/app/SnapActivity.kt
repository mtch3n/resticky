package dev.resticky.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SnapActivity : ComponentActivity() {
    private var snap: File? = null
    private val take = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val app = application as HubApp
        val file = snap
        if (ok && file != null) app.scope.launch {
            val message = app.photos.save(file)
            withContext(Dispatchers.Main) { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
        } else file?.delete()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = savedInstanceState?.getString("snap")
        if (saved != null) {
            snap = File(saved)
            return
        }
        val (file, uri) = (application as HubApp).photos.newSnap()
        snap = file
        take.launch(uri)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        snap?.let { outState.putString("snap", it.path) }
    }
}

class SnapTile : TileService() {
    override fun onClick() {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, SnapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        if (isLocked) unlockAndRun { startActivityAndCollapse(intent) } else startActivityAndCollapse(intent)
    }
}
