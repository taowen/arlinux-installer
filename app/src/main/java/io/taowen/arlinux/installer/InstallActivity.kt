package io.taowen.arlinux.installer

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException

class InstallActivity : ComponentActivity() {
    private lateinit var model: InstallModel
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) model.state.value?.apk?.let(::install)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[InstallModel::class.java]
        val mint = Color.rgb(113, 219, 190)
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        fun text(value: String, size: Float) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(12))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(32), dp(28), dp(32))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24))
            background = GradientDrawable().apply { setColor(Color.rgb(26, 36, 43)); cornerRadius = dp(24).toFloat() }
        }
        card.addView(ImageView(this).apply { setImageResource(R.drawable.icon); contentDescription = null },
            LinearLayout.LayoutParams(dp(72), dp(72)))
        card.addView(text(getString(R.string.app_name), 28f).apply { typeface = Typeface.DEFAULT_BOLD })
        card.addView(text(getString(R.string.intro), 17f).apply { setTextColor(Color.rgb(190, 202, 208)) })
        val status = text(getString(R.string.checking), 16f)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val install = Button(this).apply {
            text = getString(R.string.install); textSize = 18f; isAllCaps = false
            setTextColor(Color.rgb(8, 35, 29)); backgroundTintList = android.content.res.ColorStateList.valueOf(mint)
            minHeight = dp(56)
        }
        val cancel = Button(this).apply { text = getString(R.string.cancel); isAllCaps = false }
        card.addView(status); card.addView(progress, LinearLayout.LayoutParams(-1, dp(12)))
        card.addView(install, LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(20) })
        card.addView(cancel, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(card, LinearLayout.LayoutParams(-1, -2))
        root.addView(text(getString(R.string.website), 15f).apply {
            gravity = Gravity.CENTER; setTextColor(mint)
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://arlinux.com"))) }
        })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        install.setOnClickListener {
            val apk = model.state.value?.apk
            if (apk == null) model.start() else install(apk)
        }
        cancel.setOnClickListener { model.cancel() }
        model.state.observe(this) { current ->
            status.text = getString(current.message, *current.args.toTypedArray())
            install.isEnabled = !current.running
            install.text = getString(if (current.error) R.string.retry else R.string.install)
            cancel.visibility = if (current.running) View.VISIBLE else View.GONE
            progress.visibility = if (current.running) View.VISIBLE else View.GONE
            progress.isIndeterminate = current.percent < 0
            if (current.percent >= 0) progress.progress = current.percent
            if (current.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (current.apk != null && !model.installationPresented) {
                model.installationPresented = true
                install(current.apk)
            }
        }
    }

    private fun install(apk: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.permission, Toast.LENGTH_LONG).show()
            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.installer.files", apk)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newUri(contentResolver, "ARLinux", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}

data class InstallState(val message: Int, val args: List<Any> = emptyList(), val percent: Int = -1,
    val running: Boolean = false, val apk: File? = null, val error: Boolean = false)

class InstallModel(application: Application) : AndroidViewModel(application) {
    val state = MutableLiveData(InstallState(R.string.idle))
    var installationPresented = false
    private val worker = Executors.newSingleThreadExecutor()
    private var download: GithubDownloader? = null
    fun start() {
        if (state.value?.running == true) return
        installationPresented = false
        state.value = InstallState(R.string.checking, running = true)
        val current = GithubDownloader(getApplication(), state::postValue)
        download = current
        worker.execute {
            try {
                val apk = current.download()
                state.postValue(InstallState(R.string.ready, apk = apk))
            } catch (_: CancellationException) {
                state.postValue(InstallState(R.string.cancelled))
            } catch (error: Exception) {
                android.util.Log.w("ARLinuxInstaller", "Download failed: ${error.javaClass.simpleName}: ${error.message}")
                state.postValue(InstallState(R.string.failed, listOf(error.message ?: error.javaClass.simpleName), error = true))
            }
        }
    }
    fun cancel() { download?.cancel() }
    override fun onCleared() { cancel(); worker.shutdownNow() }
}
