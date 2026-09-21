package kr.family.homeway

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kr.family.homeway.data.AppRepository

class HomewayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("family_messages", "가족 대화와 위치 알림", NotificationManager.IMPORTANCE_HIGH)
        )
        val repo = AppRepository(this)
        // Preserve the child's saved choice. Only a system sticky-service restart or a
        // visible Activity can resume it; Application never launches location work.
        if (repo.sharingEnabled) {
            repo.noteTrackingStatus("자동 위치 공유 재개 대기 · 앱을 열면 다시 시작해요.")
        }
    }
}
