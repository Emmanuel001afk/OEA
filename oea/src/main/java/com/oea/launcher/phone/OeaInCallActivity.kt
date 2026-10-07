package com.oea.launcher.phone
import android.app.Activity
import android.os.Bundle
import android.telecom.Call
import android.view.Gravity
import android.widget.*
class OeaInCallActivity:Activity(){
 override fun onCreate(s:Bundle?){super.onCreate(s);render()}
 override fun onResume(){super.onResume();render()}
 private fun render(){val call=OeaCallState.current?:run{finish();return};val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(28,40,28,40);setBackgroundColor(0xFF0C0F15.toInt())}
 root.addView(TextView(this).apply{text=call.details.handle?.schemeSpecificPart?:"Unknown caller";textSize=28f;gravity=Gravity.CENTER;setTextColor(0xFFFFFFFF.toInt())},LinearLayout.LayoutParams(-1,90))
 root.addView(TextView(this).apply{text=when(call.state){Call.STATE_RINGING->"Incoming call";Call.STATE_DIALING->"Dialing…";Call.STATE_ACTIVE->"Active";Call.STATE_HOLDING->"On hold";else->"Call"};textSize=16f;gravity=Gravity.CENTER;setTextColor(0xFF9AA2B1.toInt())},LinearLayout.LayoutParams(-1,60))
 val b=LinearLayout(this).apply{gravity=Gravity.CENTER}
 b.addView(btn("ANSWER"){runCatching{call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)};render()});b.addView(btn("HANG UP"){runCatching{call.disconnect()};finish()});b.addView(btn("REJECT"){runCatching{call.reject(false,null)};finish()});b.addView(btn("SPEAKER"){runCatching{getSystemService(android.media.AudioManager::class.java).isSpeakerphoneOn=true}})
 root.addView(b);setContentView(root)}
 private fun btn(t:String,a:()->Unit)=Button(this).apply{text=t;setOnClickListener{a()}}
}