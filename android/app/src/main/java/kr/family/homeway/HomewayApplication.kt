package kr.family.homeway

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kr.family.homeway.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class HomewayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("family_messages", "가족 대화와 위치 알림", NotificationManager.IMPORTANCE_HIGH)
        )
        val repo = AppRepository(this)
        // Android process death terminates this session. Never silently restart tracking after reboot.
        if (repo.sharingEnabled) {
            repo.sharingEnabled = false
            repo.noteTrackingStatus("앱이 다시 시작되어 자동 공유가 멈췄어요. 설정에서 다시 켜 주세요.")
            if (repo.configured && repo.isChild) CoroutineScope(Dispatchers.IO).launch {
                runCatching { repo.enqueueEventOnly("sharing_status", JSONObject().put("enabled", false)) }
            }
        }
    }
}
