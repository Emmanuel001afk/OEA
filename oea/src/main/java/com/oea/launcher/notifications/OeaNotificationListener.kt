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

/**
 * Live notification state shared with OEA Home: per-app badge counts and the
 * current media session for At a Glance. Observers are called on the main thread.
 */
object OeaNotificationState {
 private val active=LinkedHashMap<String,StatusBarNotification>()
 private val listeners=LinkedHashSet<()->Unit>()
 private val main=android.os.Handler(android.os.Looper.getMainLooper())
 private var pending=false
 @Volatile var connected=false
  private set
 @Synchronized fun update(sbn:StatusBarNotification){active[sbn.key]=sbn;changed()}
 @Synchronized fun remove(sbn:StatusBarNotification){active.remove(sbn.key);changed()}
 @Synchronized fun clear(){active.clear();changed()}
 @Synchronized fun replaceAll(values:Array<StatusBarNotification>?){active.clear();values?.forEach{active[it.key]=it};changed()}
 fun setConnected(value:Boolean){connected=value;changed()}
 /** Notifications that should produce a badge (ongoing/system noise excluded). */
 @Synchronized fun countForPackage(packageName:String)=active.values.count{it.packageName==packageName&&it.isClearable&&!it.isOngoing&&(it.notification.flags and Notification.FLAG_GROUP_SUMMARY)==0}
 @Synchronized fun media():StatusBarNotification?=active.values.lastOrNull{sbn->val n=sbn.notification;n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)||n.category==Notification.CATEGORY_TRANSPORT}
 fun addListener(l:()->Unit){synchronized(listeners){listeners+=l}}
 fun removeListener(l:()->Unit){synchronized(listeners){listeners-=l}}
 private fun changed(){
  if(pending)return
  pending=true
  // Coalesce bursts (e.g. a chat app posting several notifications at once).
  main.postDelayed({pending=false;val copy=synchronized(listeners){listeners.toList()};copy.forEach{runCatching{it()}}},120)
 }
}
class OeaNotificationListener:NotificationListenerService(){
 private var island:View?=null
 override fun onNotificationPosted(sbn:StatusBarNotification){OeaNotificationState.update(sbn);if(isMediaNotification(sbn))showDynamicIsland(sbn)}
 override fun onNotificationRemoved(sbn:StatusBarNotification){OeaNotificationState.remove(sbn);if(isMediaNotification(sbn))hideDynamicIsland()}
 override fun onListenerConnected(){OeaNotificationState.setConnected(true);runCatching{OeaNotificationState.replaceAll(activeNotifications)}}
 override fun onListenerDisconnected(){OeaNotificationState.clear();OeaNotificationState.setConnected(false);hideDynamicIsland()}
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