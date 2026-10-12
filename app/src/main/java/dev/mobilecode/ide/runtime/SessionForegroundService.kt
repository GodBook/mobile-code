package dev.mobilecode.ide.runtime

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.mobilecode.ide.MainActivity
import dev.mobilecode.ide.MobileCodeApp
import dev.mobilecode.ide.R
import dev.mobilecode.ide.core.SessionPhase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class SessionForegroundService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val manager get()=(application as MobileCodeApp).sessions
    private var latestStartId=0
    inner class LocalBinder: Binder() { fun supervisor()=manager }
    override fun onBind(intent: Intent?): IBinder = LocalBinder()
    override fun onCreate() {
        super.onCreate()
        val notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("execution","本地代码任务",NotificationManager.IMPORTANCE_LOW))
        promote("正在准备本地任务")
        scope.launch {
            manager.state.map { it.phase }.distinctUntilChanged().collect {
                val state=manager.state.value
                if(state.active) notifications.notify(1,notification(when(state.phase) {
                    SessionPhase.PREPARING -> "准备源码快照"; SessionPhase.BUILDING -> "正在编译"; SessionPhase.STARTING -> "启动运行环境"
                    SessionPhase.PAUSED -> "调试已暂停"; else -> "程序正在运行"
                })) else if(state.phase!=SessionPhase.IDLE && latestStartId>0) {
                    if(stopSelfResult(latestStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        latestStartId=startId
        // A new task may arrive while the preceding service instance is stopping.
        // Every startForegroundService request must be acknowledged, including reuse.
        promote(if(manager.state.value.active) "正在执行本地任务" else "本地任务已结束")
        if(intent?.action=="STOP") scope.launch { manager.stop() }
        if(!manager.state.value.active && stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
        return START_NOT_STICKY
    }
    private fun promote(message: String) {
        val value=notification(message)
        if(Build.VERSION.SDK_INT>=34) startForeground(1,value,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1,value)
    }
    private fun notification(message: String): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,SessionForegroundService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this,"execution").setSmallIcon(R.drawable.ic_launcher).setContentTitle("Mobile Code").setContentText(message)
            .setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_media_pause,"停止",stop).setOnlyAlertOnce(true).build()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
