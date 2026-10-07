package com.oea.launcher.phone
import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.view.Gravity
import android.widget.*
import java.text.DateFormat
import java.util.Date
class OeaPhoneActivity : Activity() {
 private lateinit var number: EditText; private lateinit var list: LinearLayout
 override fun onCreate(s: Bundle?){super.onCreate(s);build();requestNeededPermissions()}
 override fun onResume(){super.onResume();if(::list.isInitialized)loadRecents()}
 private fun build(){
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(22),dp(18),dp(18));setBackgroundColor(0xFF0C0F15.toInt())}
  root.addView(TextView(this).apply{text="OEA Phone";textSize=28f;setTextColor(0xFFFFFFFF.toInt());setTypeface(typeface,android.graphics.Typeface.BOLD)},LinearLayout.LayoutParams(-1,dp(48)))
  root.addView(TextView(this).apply{text=if(isDefaultDialer())"OEA is your default phone app" else "OEA Phone • set as default for full call handling";textSize=13f;setTextColor(0xFF9AA2B1.toInt())},LinearLayout.LayoutParams(-1,dp(38)))
  number=EditText(this).apply{hint="Phone number or USSD";setSingleLine(true);inputType=android.text.InputType.TYPE_CLASS_PHONE;textSize=22f;setTextColor(0xFFFFFFFF.toInt());setHintTextColor(0xFF9AA2B1.toInt())}
  root.addView(number,LinearLayout.LayoutParams(-1,dp(58)))
  root.addView(Button(this).apply{text="CALL";setOnClickListener{placeCall()}},LinearLayout.LayoutParams(-1,dp(50)))
  val actions=LinearLayout(this).apply{gravity=Gravity.CENTER}
  actions.addView(button("Default Phone"){requestDefaultDialer()},LinearLayout.LayoutParams(0,dp(48),1f))
  actions.addView(button("Contacts"){openContacts()},LinearLayout.LayoutParams(0,dp(48),1f))
  actions.addView(button("Blocker"){runCatching{startActivity(Intent().setClassName(this@OeaPhoneActivity,"com.oea.launcher.OeaCallBlocker"))}},LinearLayout.LayoutParams(0,dp(48),1f))
  root.addView(actions)
  root.addView(TextView(this).apply{text="Recent calls";textSize=19f;setTextColor(0xFFFFFFFF.toInt());setPadding(0,dp(18),0,dp(8))})
  list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
  root.addView(ScrollView(this).apply{addView(list)},LinearLayout.LayoutParams(-1,0,1f));setContentView(root);loadRecents()
 }
 private fun button(t:String,a:()->Unit)=Button(this).apply{text=t;textSize=10f;setOnClickListener{a()}}
 private fun requestNeededPermissions(){val p=arrayOf(Manifest.permission.READ_CALL_LOG,Manifest.permission.READ_CONTACTS,Manifest.permission.CALL_PHONE);val m=p.filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED};if(m.isNotEmpty())requestPermissions(m.toTypedArray(),9001)}
 private fun placeCall(){val raw=number.text.toString().trim();if(raw.isEmpty())return;val uri=Uri.parse("tel:"+Uri.encode(raw));runCatching{if(checkSelfPermission(Manifest.permission.CALL_PHONE)==PackageManager.PERMISSION_GRANTED)startActivity(Intent(Intent.ACTION_CALL,uri))else startActivity(Intent(Intent.ACTION_DIAL,uri))}.onFailure{Toast.makeText(this,"Unable to start the call.",Toast.LENGTH_SHORT).show()}}
 private fun openContacts(){runCatching{startActivity(Intent(Intent.ACTION_VIEW,ContactsContract.Contacts.CONTENT_URI))}}
 private fun requestDefaultDialer(){if(android.os.Build.VERSION.SDK_INT>=29){val rm=getSystemService(RoleManager::class.java);if(rm?.isRoleAvailable(RoleManager.ROLE_DIALER)==true&&!rm.isRoleHeld(RoleManager.ROLE_DIALER))startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER),9002)}}
 private fun isDefaultDialer()=android.os.Build.VERSION.SDK_INT>=29&&getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER)==true
 private fun loadRecents(){list.removeAllViews();if(checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED)return;val p=arrayOf(CallLog.Calls.NUMBER,CallLog.Calls.TYPE,CallLog.Calls.DATE);runCatching{contentResolver.query(CallLog.Calls.CONTENT_URI,p,null,null,CallLog.Calls.DATE+" DESC")?.use{c:Cursor->var n=0;while(c.moveToNext()&&n++<30){val num=c.getString(0).orEmpty();val type=when(c.getInt(1)){CallLog.Calls.INCOMING_TYPE->"Incoming";CallLog.Calls.OUTGOING_TYPE->"Outgoing";CallLog.Calls.MISSED_TYPE->"Missed";else->"Call"};val d=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(c.getLong(2)));list.addView(TextView(this).apply{text="$num\n$type • $d";textSize=14f;setTextColor(if(type=="Missed")0xFFFF8A8A.toInt() else 0xFFFFFFFF.toInt());setPadding(dp(12),dp(12),dp(12),dp(12));setOnClickListener{number.setText(num);number.setSelection(number.text.length)}},LinearLayout.LayoutParams(-1,dp(68)))}}}}
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}