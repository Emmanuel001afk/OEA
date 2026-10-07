package com.oea.launcher.notifications

import android.app.Notification
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.session.MediaController
import android.media.session.MediaSession
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

object OeaNotificationState {
 private val active=LinkedHashMap<String,StatusBarNotification>()
 @Synchronized fun update(sbn:StatusBarNotification){active[sbn.key]=sbn}
 @Synchronized fun remove(sbn:StatusBarNotification){active.remove(sbn.key)}
 @Synchronized fun clear(){active.clear()}
 @Synchronized fun countForPackage(packageName:String)=active.values.count{it.packageName==packageName}
}
class OeaNotificationListener:NotificationListenerService(){
 private var island:View?=null
 override fun onNotificationPosted(sbn:StatusBarNotification){OeaNotificationState.update(sbn);if(isMediaNotification(sbn))showDynamicIsland(sbn)}
 override fun onNotificationRemoved(sbn:StatusBarNotification){OeaNotificationState.remove(sbn);if(isMediaNotification(sbn))hideDynamicIsland()}
 override fun onListenerDisconnected(){OeaNotificationState.clear();hideDynamicIsland()}
 private fun isMediaNotification(sbn:StatusBarNotification):Boolean{
  val n=sbn.notification;val pkg=sbn.packageName.lowercase()
  return n.category==Notification.CATEGORY_TRANSPORT||pkg.contains("spotify")||n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
 }
 private fun showDynamicIsland(sbn:StatusBarNotification){
  val wm=getSystemService(WINDOW_SERVICE) as WindowManager
  val n=sbn.notification
  val title=n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?:n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?:sbn.packageName.substringAfterLast('.')
  val mediaText=n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
  val controller=runCatching{
   val token=if(android.os.Build.VERSION.SDK_INT>=33)n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION,MediaSession.Token::class.java) else @Suppress("DEPRECATION") n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
   token?.let{MediaController(this,it)}
  }.getOrNull()
  val root=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL;setPadding(16,8,16,8);gravity=Gravity.CENTER
   background=GradientDrawable().apply{setColor(Color.BLACK);cornerRadius=60f}
  }
  val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
  top.addView(TextView(this).apply{text="●";textSize=10f;setTextColor(Color.WHITE);setPadding(0,0,10,0)})
  top.addView(TextView(this).apply{text=if(mediaText.isBlank())title else "$title  •  $mediaText";textSize=12f;setTextColor(Color.WHITE);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,34,1f))
  root.addView(top)
  val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;visibility=View.GONE}
  fun ctl(label:String,action:()->Unit)=TextView(this).apply{text=label;textSize=11f;setTextColor(Color.WHITE);setPadding(12,8,12,8);setOnClickListener{action()}}
  controller?.let{mc->
   controls.addView(ctl("◀"){mc.transportControls.skipToPrevious()})
   controls.addView(ctl(if(mc.playbackState?.state==android.media.session.PlaybackState.STATE_PLAYING)"Ⅱ" else "▶"){
    if(mc.playbackState?.state==android.media.session.PlaybackState.STATE_PLAYING)mc.transportControls.pause() else mc.transportControls.play()
   })
   controls.addView(ctl("▶|"){mc.transportControls.skipToNext()})
  }
  controls.addView(ctl("OPEN"){runCatching{sbn.notification.contentIntent?.send()}})
  root.addView(controls)
  root.setOnClickListener{controls.visibility=if(controls.visibility==View.VISIBLE)View.GONE else View.VISIBLE}
  val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,if(android.os.Build.VERSION.SDK_INT>=26)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,android.graphics.PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL;y=24}
  hideDynamicIsland();runCatching{wm.addView(root,params);island=root}
 }
 private fun hideDynamicIsland(){island?.let{runCatching{(getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)}};island=null}
}