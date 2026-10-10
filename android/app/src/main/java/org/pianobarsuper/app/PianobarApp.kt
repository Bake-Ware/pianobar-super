package org.pianobarsuper.app

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.pianobarsuper.app.data.PhoneLibrary
import org.pianobarsuper.app.data.Repository
import org.pianobarsuper.app.update.Updater
import org.pianobarsuper.app.widget.NowPlayingWidgets

class PianobarApp : Application() {
    lateinit var repo: Repository
        private set
    lateinit var phone: PhoneLibrary
        private set

    override fun onCreate() {
        super.onCreate()
        CookieManager.getInstance().setAcceptCookie(true)
        repo = Repository(this)
        phone = PhoneLibrary(this, repo.scope) { repo.api.value }
        // Keep the live connection open while any screen is visible.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = repo.retain()
            override fun onStop(owner: LifecycleOwner) = repo.release()
        })
        Updater.init(this)
        NowPlayingWidgets.init(this)
    }
}
