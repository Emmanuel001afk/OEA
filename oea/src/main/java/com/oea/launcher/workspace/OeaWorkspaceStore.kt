package com.oea.launcher.workspace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
class OeaWorkspaceStore private constructor(context:Context){
 data class Item(val id:String,val packageName:String,val className:String,val page:Int,val cell:Int,val folderId:String?=null)
 data class Folder(val id:String,val title:String,val page:Int,val cell:Int,val members:List<String>)
 private val prefs=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
 fun pages()=prefs.getInt(KEY_PAGES,1).coerceIn(1,MAX_PAGES);fun setPages(v:Int){prefs.edit().putInt(KEY_PAGES,v.coerceIn(1,MAX_PAGES)).apply()};fun items()=readItems();fun getCurrentPage()=prefs.getInt(KEY_CURRENT_PAGE,0).coerceIn(0,pages()-1);fun setCurrentPage(v:Int){prefs.edit().putInt(KEY_CURRENT_PAGE,v.coerceIn(0,pages()-1)).apply()}
 fun dock()=prefs.getStringSet(KEY_DOCK,emptySet())?.toList()?.sortedBy{it.substringBefore("|").toIntOrNull()?:Int.MAX_VALUE}?.map{it.substringAfter("|")}?:emptyList();fun setDock(values:List<String>){prefs.edit().putStringSet(KEY_DOCK,values.take(DOCK_SLOTS).mapIndexed{i,v->"$i|$v"}.toSet()).apply()}
 fun folders():List<Folder>{val raw=prefs.getString(KEY_FOLDERS,null)?:return emptyList();return runCatching{val a=JSONArray(raw);(0 until a.length()).map{i->val o=a.getJSONObject(i);val m=o.optJSONArray("members");Folder(o.getString("id"),o.optString("title","Folder"),o.optInt("page",0),o.optInt("cell",0),if(m==null)emptyList()else(0 until m.length()).map(m::getString))}}.getOrDefault(emptyList())}
 fun replaceItems(values:List<Item>){val a=JSONArray();values.forEach{i->a.put(JSONObject().apply{put("id",i.id);put("package",i.packageName);put("class",i.className);put("page",i.page);put("cell",i.cell);i.folderId?.let{put("folder",it)}})};prefs.edit().putString(KEY_ITEMS,a.toString()).apply()}
 fun replaceFolders(values:List<Folder>){val a=JSONArray();values.forEach{f->a.put(JSONObject().apply{put("id",f.id);put("title",f.title);put("page",f.page);put("cell",f.cell);put("members",JSONArray(f.members))})};prefs.edit().putString(KEY_FOLDERS,a.toString()).apply()}
 fun ensureSeeded(apps:List<Triple<String,String,String>>){if(prefs.contains(KEY_SEEDED))return;val dockKeys=apps.take(DOCK_SLOTS).map{key(it.first,it.second)};val homeApps=apps.drop(DOCK_SLOTS).take(20);replaceItems(homeApps.mapIndexed{i,a->Item(key(a.first,a.second),a.first,a.second,0,i)});setDock(dockKeys);setPages(1);setCurrentPage(0);prefs.edit().putBoolean(KEY_SEEDED,true).apply()}
 fun clearMissing(valid:Set<String>){replaceItems(items().filter{it.id in valid});setDock(dock().filter(valid::contains));replaceFolders(folders().map{it.copy(members=it.members.filter(valid::contains))}.filter{it.members.isNotEmpty()})}
 companion object{const val DOCK_SLOTS=5;const val MAX_PAGES=12;private const val PREFS="oea_workspace";private const val KEY_ITEMS="items";private const val KEY_FOLDERS="folders";private const val KEY_DOCK="dock";private const val KEY_PAGES="pages";private const val KEY_SEEDED="seeded";private const val KEY_CURRENT_PAGE="current_page";@Volatile private var instance:OeaWorkspaceStore?=null;fun get(context:Context)=instance?:synchronized(this){instance?:OeaWorkspaceStore(context).also{instance=it}};fun key(p:String,c:String)="$p/$c"}
 private fun readItems():List<Item>{val raw=prefs.getString(KEY_ITEMS,null)?:return emptyList();return runCatching{val a=JSONArray(raw);(0 until a.length()).map{i->val o=a.getJSONObject(i);Item(o.getString("id"),o.getString("package"),o.getString("class"),o.optInt("page",0),o.optInt("cell",0),o.optString("folder","").takeIf(String::isNotBlank))}}.getOrDefault(emptyList())}
}
