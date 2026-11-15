package top.ntutn.awall.update

import java.io.File

internal fun requireUpdateApk(root: File, localPath: String): File {
    val directory = root.canonicalFile
    val file = File(localPath).canonicalFile
    require(file.path.startsWith(directory.path + File.separator)) { "File is outside the update directory" }
    require(file.extension.equals("apk", ignoreCase = true) && file.isFile && file.length() > 0) {
        "Update APK is missing or incomplete"
    }
    return file
}
