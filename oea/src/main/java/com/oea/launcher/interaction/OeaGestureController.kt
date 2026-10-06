package com.oea.launcher.interaction
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
class OeaGestureController(view:View,private val onSwipeUp:()->Unit={},private val onSwipeDown:()->Unit={}):GestureDetector.SimpleOnGestureListener(),View.OnTouchListener{private val detector=GestureDetector(view.context,this);override fun onDown(e:MotionEvent)=true;override fun onFling(e1:MotionEvent?,e2:MotionEvent,vx:Float,vy:Float):Boolean{if(e1==null)return false;val dx=e2.x-e1.x;val dy=e2.y-e1.y;if(kotlin.math.abs(dy)>kotlin.math.abs(dx)&&kotlin.math.abs(dy)>120f){if(dy<0)onSwipeUp()else onSwipeDown();return true};return false};override fun onTouch(v:View,event:MotionEvent)=detector.onTouchEvent(event)}
