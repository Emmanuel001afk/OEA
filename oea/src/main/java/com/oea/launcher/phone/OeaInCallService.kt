package com.oea.launcher.phone
import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
class OeaInCallService:InCallService(){
 override fun onCallAdded(call:Call){super.onCallAdded(call);OeaCallState.current=call;runCatching{startActivity(Intent(this,OeaInCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))}}
 override fun onCallRemoved(call:Call){if(OeaCallState.current==call)OeaCallState.current=null;super.onCallRemoved(call)}
}
object OeaCallState{@Volatile var current:Call?=null}