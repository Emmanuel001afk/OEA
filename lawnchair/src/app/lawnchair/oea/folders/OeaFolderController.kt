package app.lawnchair.oea.folders

class OeaFolderController {
    fun mergeMembers(existing: List<String>, added: String): List<String> = (existing + added).distinct()
    fun removeMember(members: List<String>, id: String): List<String> = members.filterNot { it == id }
}