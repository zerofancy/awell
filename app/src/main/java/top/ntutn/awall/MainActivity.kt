package top.ntutn.awall

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import top.ntutn.awall.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.button.setOnClickListener {
            val intent = Intent()
            intent.action = WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER
//            val p = AWallpaperService::class.java.`package`?.name ?: return@setOnClickListener
//            val c = AWallpaperService::class.java.canonicalName ?: return@setOnClickListener
//            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(p, c))
            startActivityForResult(intent, 0)
        }
    }
}