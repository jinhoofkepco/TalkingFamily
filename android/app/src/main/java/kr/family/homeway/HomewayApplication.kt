package kr.family.homeway

import android.app.Application
import kr.family.homeway.data.AppRepository
import kr.family.homeway.data.FamilyNotifications

class HomewayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FamilyNotifications.initialize(this)
        val repo = AppRepository(this)
        // Preserve the child's saved choice. Only a system sticky-service restart or a
        // visible Activity can resume it; Application never launches location work.
        if (repo.sharingEnabled) {
            repo.noteTrackingStatus("자동 위치 공유 재개 대기 · 앱을 열면 다시 시작해요.")
        }
    }
}
