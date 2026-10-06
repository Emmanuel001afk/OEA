package com.oea.launcher.folders
class OeaFolderController{fun mergeMembers(existing:List<String>,added:String)= (existing+added).distinct();fun removeMember(members:List<String>,id:String)=members.filterNot{it==id}}
