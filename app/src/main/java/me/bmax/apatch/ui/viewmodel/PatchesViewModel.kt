package me.bmax.apatch.ui.viewmodel

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.bmax.apatch.APApplication
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.R
import me.bmax.apatch.apApp
import me.bmax.apatch.util.Version
import me.bmax.apatch.util.getSafeDownloadsDir
import me.bmax.apatch.util.copyAndClose
import me.bmax.apatch.util.copyAndCloseOut
import me.bmax.apatch.util.createRootShellSafe
import me.bmax.apatch.util.inputStream
import me.bmax.apatch.util.shellForResult
import me.bmax.apatch.util.writeTo
import me.bmax.apatch.util.clearJailbreakMarker
import org.ini4j.Ini
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import me.bmax.apatch.util.getFileNameFromUri
import me.bmax.apatch.util.getKmi
import me.bmax.apatch.util.ModuleBackupUtils
import me.bmax.apatch.util.SafeUriResolver
import me.bmax.apatch.ui.screen.selectedKPImg
import me.bmax.apatch.ui.screen.selectedBootImage

private const val TAG = "PatchViewModel"

class PatchesViewModel : ViewModel() {

    enum class PatchMode(val sId: Int) {
        PATCH_ONLY(R.string.patch_mode_bootimg_patch),
        PATCH_AND_INSTALL(R.string.patch_mode_patch_and_install),
        INSTALL_TO_NEXT_SLOT(R.string.patch_mode_install_to_next_slot),
        RESTORE(R.string.patch_mode_restore),
        UNPATCH(R.string.patch_mode_uninstall_patch),
        LKM_PATCH_ONLY(R.string.patch_mode_lkm_bootimg_patch),
        LKM_PATCH_AND_INSTALL(R.string.patch_mode_lkm_patch_and_install),
        LKM_INSTALL_TO_NEXT_SLOT(R.string.patch_mode_lkm_install_to_next_slot),
        LKM_RESTORE(R.string.patch_mode_lkm_restore);

        val isLkm: Boolean
            get() = name.startsWith("LKM_")

        val isLkmInstall: Boolean
            get() = this == LKM_PATCH_AND_INSTALL || this == LKM_INSTALL_TO_NEXT_SLOT

        val isLkmPatchOnly: Boolean
            get() = this == LKM_PATCH_ONLY

        val isLkmRestore: Boolean
            get() = this == LKM_RESTORE
    }

    var bootSlot by mutableStateOf("")
    var bootDev by mutableStateOf("")
    var kimgInfo by mutableStateOf(KPModel.KImgInfo("", false))
    var kpimgInfo by mutableStateOf(KPModel.KPImgInfo("", "", "", "", ""))
    var superkey by mutableStateOf(APApplication.superKey)
    var existedExtras = mutableStateListOf<KPModel.IExtraInfo>()
    var newExtras = mutableStateListOf<KPModel.IExtraInfo>()
    var newExtrasFileName = mutableListOf<String>()

    var running by mutableStateOf(false)
    var patching by mutableStateOf(false)
    var patchdone by mutableStateOf(false)
    var needReboot by mutableStateOf(false)
    var useCustomKPImg by mutableStateOf(false)
    var customKPImgFileName by mutableStateOf("")
    var lkmReady by mutableStateOf(false)
    var lkmKmi by mutableStateOf("")
    var lkmImageReady by mutableStateOf(false)

    var error by mutableStateOf("")
    var patchLog by mutableStateOf("")

    private val patchDir: ExtendedFile = FileSystemManager.getLocal().getFile(apApp.filesDir.parent, "patch")
    private var srcBoot: ExtendedFile = patchDir.getChildFile("boot.img")
    private var shell: Shell? = null
    private var prepared: Boolean = false

    private fun getShell(): Shell {
        return shell ?: createRootShellSafe(false).also { shell = it }
    }

    private fun prepare() {
        val sh = getShell()
        sh.newJob().add("rm -rf ${patchDir.path}").exec()
        
        patchDir.deleteRecursively()
        patchDir.mkdirs()
        val execs = listOf(
            "libkptools.so", "libbusybox.so", "libkpatch.so", "libbootctl.so", "libapd.so", "libapinit.so"
        )
        error = ""

        val info = apApp.applicationInfo
        val libs = File(info.nativeLibraryDir).listFiles { _, name ->
            execs.contains(name)
        } ?: emptyArray()

        for (lib in libs) {
            val name = lib.name.substring(3, lib.name.length - 3)
            try {
                Os.symlink(lib.path, "$patchDir/$name")
            } catch (e: Exception) {
                lib.inputStream().copyAndClose(File(patchDir, name).outputStream())
            }
        }

        // Extract scripts
        for (script in listOf(
            "boot_patch.sh", "boot_unpatch.sh", "boot_extract.sh", "util_functions.sh", "kpimg", "boot_flash.sh"
        )) {
            val dest = File(patchDir, script)
            apApp.assets.open(script).writeTo(dest)
        }

    }

    private fun parseKpimg() {
        val result = shellForResult(
            getShell(), "cd $patchDir", "./kptools -l -k kpimg"
        )

        if (result.isSuccess) {
            try {
                val ini = Ini(StringReader(result.out.joinToString("\n")))
                val kpimg = ini["kpimg"]
                if (kpimg != null) {
                    kpimgInfo = KPModel.KPImgInfo(
                        kpimg["version"].toString(),
                        kpimg["compile_time"].toString(),
                        kpimg["config"].toString(),
                        APApplication.superKey,     // current key
                        kpimg["root_superkey"].toString(),   // empty
                    )
                } else {
                    error += "parse kpimg error\n"
                }
            } catch (e: Exception) {
                Log.e(TAG, "parseKpimg INI error: ${e.message}")
                error += "parse kpimg error: ${e.message}\n"
            }
        } else {
            error = result.err.joinToString("\n")
        }
    }

    private fun parseBootimg(bootimg: String) {
        val result = shellForResult(
            getShell(),
            "cd $patchDir",
            "./kptools unpacknolog $bootimg",
            "./kptools -l -i kernel",
        )
        if (result.isSuccess) {
            try {
                val ini = Ini(StringReader(result.out.joinToString("\n")))
                Log.d(TAG, "kernel image info: $ini")

                val kernel = ini["kernel"]
                if (kernel == null) {
                    error += "empty kernel section"
                    Log.d(TAG, error)
                    return
                }
                kimgInfo = KPModel.KImgInfo(kernel["banner"].toString(), kernel["patched"].toBoolean())
                if (kimgInfo.patched) {
                    val superkey = ini["kpimg"]?.getOrDefault("superkey", "") ?: ""
                    kpimgInfo.superKey = superkey
                    if (superkey.isNotEmpty()) {
                        this.superkey = superkey
                    }
                    var kpmNum = kernel["extra_num"]?.toInt()
                    if (kpmNum == null) {
                        val extras = ini["extras"]
                        kpmNum = extras?.get("num")?.toInt()
                    }
                    if (kpmNum != null && kpmNum > 0) {
                        for (i in 0..<kpmNum) {
                            val extra = ini["extra $i"]
                            if (extra == null) {
                                error += "empty extra section"
                                break
                            }
                            val type = KPModel.ExtraType.valueOf(extra["type"]!!.uppercase())
                            val name = extra["name"].toString()
                            val args = extra["args"].toString()
                            var event = extra["event"].toString()
                            if (event.isEmpty()) {
                                event = KPModel.TriggerEvent.PRE_KERNEL_INIT.event
                            }
                            if (type == KPModel.ExtraType.KPM) {
                                val kpmInfo = KPModel.KPMInfo(
                                    type, name, event, args,
                                    extra["version"].toString(),
                                    extra["license"].toString(),
                                    extra["author"].toString(),
                                    extra["description"].toString(),
                                )
                                existedExtras.add(kpmInfo)
                            }
                        }

                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "parseBootimg INI error: ${e.message}")
                error += "parse boot image error: ${e.message}\n"
            }
        } else {
            error += result.err.joinToString("\n")
        }
    }

    fun copyAndParseBootimg(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            if (running) return@launch
            running = true
            try {
                uri.inputStream().buffered().use { src ->
                    srcBoot.also {
                        src.copyAndCloseOut(it.newOutputStream())
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "copy boot image error: $e")
            }
            parseBootimg(srcBoot.path)
            running = false
        }
    }

    private fun extractAndParseBootimg(mode: PatchMode) {
        var cmdBuilder = "./boot_extract.sh"

        if (mode == PatchMode.INSTALL_TO_NEXT_SLOT || mode == PatchMode.LKM_INSTALL_TO_NEXT_SLOT) {
            cmdBuilder += " true"
        }
        if (mode.isLkm) {
            if (mode != PatchMode.LKM_INSTALL_TO_NEXT_SLOT) {
                cmdBuilder += " false"
            }
            cmdBuilder += " true"
        }

        val result = shellForResult(
            getShell(),
            "export ASH_STANDALONE=1",
            "cd $patchDir",
            "./busybox sh $cmdBuilder",
        )

        if (result.isSuccess) {
            bootSlot = if (!result.out.toString().contains("SLOT=")) {
                ""
            } else {
                result.out.filter { it.startsWith("SLOT=") }[0].removePrefix("SLOT=")
            }
            bootDev =
                result.out.filter { it.startsWith("BOOTIMAGE=") }[0].removePrefix("BOOTIMAGE=")
            Log.i(TAG, "current slot: $bootSlot")
            Log.i(TAG, "current bootimg: $bootDev")
            srcBoot = FileSystemManager.getLocal().getFile(bootDev)
            if (mode.isLkm) {
                val kmiImage = result.out.firstOrNull { it.startsWith("KMIIMAGE=") }
                    ?.removePrefix("KMIIMAGE=")
                if (kmiImage != null && !detectLkmKmi(kmiImage)) {
                    lkmImageReady = false
                    throw IOException("Unable to detect KMI from $kmiImage")
                }
                if (mode == PatchMode.LKM_INSTALL_TO_NEXT_SLOT && kmiImage == null) {
                    lkmImageReady = false
                    throw IOException("Unable to locate the target slot kernel for KMI detection")
                }
                lkmImageReady = true
            } else {
                parseBootimg(bootDev)
            }
        } else {
            error = result.err.joinToString("\n")
        }
        running = false
    }

    private fun prepareLkmModule() {
        val kmi = lkmKmi.ifEmpty {
            kmiFromBanner(kimgInfo.banner) ?: getKmi() ?: throw IOException("Unable to detect kernel KMI")
        }
        val module = File(patchDir.path, "kernelpatch.ko")
        val assetName = "${kmi}_kernelpatch.ko"
        apApp.assets.open(assetName).use { input ->
            module.outputStream().use { output -> input.copyTo(output) }
        }
        lkmKmi = kmi
        lkmReady = module.isFile && module.length() > 0L && File(patchDir.path, "apinit").isFile
        if (!lkmReady) {
            throw IOException("LKM assets are incomplete for $kmi")
        }
    }

    private fun kmiFromBanner(banner: String): String? {
        return Regex("(.* )?(\\d+\\.\\d+)(\\S+)?(android\\d+)(.*)")
            .find(banner)
            ?.let { "${it.groupValues[4]}-${it.groupValues[2]}" }
    }

    private fun detectLkmKmi(image: String): Boolean {
        val quotedImage = image.replace("'", "'\\''")
        val result = shellForResult(
            getShell(),
            "cd ${patchDir.path}",
            "./kptools unpacknolog '$quotedImage'",
            "./kptools -l -i kernel",
        )
        if (!result.isSuccess) return false
        return runCatching {
            val ini = Ini(StringReader(result.out.joinToString("\n")))
            val banner = ini["kernel"]?.get("banner")?.toString().orEmpty()
            kmiFromBanner(banner)?.let {
                lkmKmi = it
                true
            } ?: false
        }.onFailure { Log.w(TAG, "Unable to detect LKM KMI from $image", it) }
            .getOrDefault(false)
    }

    fun prepare(mode: PatchMode) {
        viewModelScope.launch(Dispatchers.IO) {
            if (prepared) return@launch
            prepared = true

            running = true
            try {
                prepare()

                if (!mode.isLkm && selectedKPImg != null && mode == PatchMode.PATCH_ONLY) {
                    try {
                        val kpimgFile = File(patchDir, "kpimg")
                        selectedKPImg!!.inputStream().buffered().use { src ->
                            kpimgFile.also {
                                src.copyAndCloseOut(it.outputStream())
                            }
                        }
                        customKPImgFileName = getFileNameFromUri(apApp, selectedKPImg!!) ?: "kpimg"
                        useCustomKPImg = true
                    } catch (e: IOException) {
                        Log.e(TAG, "Copy custom kpimg error: $e")
                        error += "Copy custom kpimg error: ${e.message}\n"
                    }
                }

                if (selectedBootImage != null &&
                    (mode == PatchMode.PATCH_ONLY || mode == PatchMode.RESTORE || mode.isLkmPatchOnly || mode.isLkmRestore)) {
                    try {
                        selectedBootImage!!.inputStream().buffered().use { src ->
                            srcBoot.also {
                                src.copyAndCloseOut(it.newOutputStream())
                            }
                        }
                        if (mode.isLkm) {
                            lkmImageReady = true
                            detectLkmKmi(srcBoot.path)
                        } else {
                            parseBootimg(srcBoot.path)
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Copy selected boot image error: $e")
                        error += "Copy selected boot image error: ${e.message}\n"
                    }
                }

                // Resolve the target ramdisk first so inactive-slot installs use
                // the target slot's kernel when selecting the LKM KMI asset.
                if (mode.isLkmInstall) {
                    extractAndParseBootimg(mode)
                }
                if (mode.isLkm && !mode.isLkmRestore) {
                    prepareLkmModule()
                } else if (!mode.isLkm && mode != PatchMode.UNPATCH) {
                    parseKpimg()
                }
                if (!mode.isLkm && (mode == PatchMode.PATCH_AND_INSTALL || mode == PatchMode.UNPATCH || mode == PatchMode.INSTALL_TO_NEXT_SLOT)) {
                    extractAndParseBootimg(mode)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Prepare failed", e)
                error = "Initialization failed: ${e.message}"
            } finally {
                running = false
            }
        }
    }

    fun embedKPM(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            if (running) return@launch
            running = true
            error = ""

            val rand = (1..4).map { ('a'..'z').random() }.joinToString("")
            val kpmFileName = "${rand}.kpm"
            val kpmFile: ExtendedFile = patchDir.getChildFile(kpmFileName)

            Log.i(TAG, "copy kpm to: " + kpmFile.path)
            try {
                uri.inputStream().buffered().use { src ->
                    kpmFile.also {
                        src.copyAndCloseOut(it.newOutputStream())
                    }
                }

                // Auto Backup Logic
                val originalFileName = getFileNameFromUri(apApp, uri)
                launch(Dispatchers.IO) {
                     val result = ModuleBackupUtils.autoBackupModule(
                        apApp,
                        kpmFile,
                        originalFileName,
                        "KPM"
                    )
                    if (result != null && !result.startsWith("Duplicate")) {
                        Log.e(TAG, "KPM Auto backup failed: $result")
                    } else {
                        Log.d(TAG, "KPM Auto backup success")
                    }
                }

            } catch (e: IOException) {
                Log.e(TAG, "Copy kpm error: $e")
            }

            val result = shellForResult(
                getShell(), "cd $patchDir", "./kptools -l -M ${kpmFile.path}"
            )

            if (result.isSuccess) {
                try {
                    val ini = Ini(StringReader(result.out.joinToString("\n")))
                    val kpm = ini["kpm"]
                    if (kpm != null) {
                        val kpmInfo = KPModel.KPMInfo(
                            KPModel.ExtraType.KPM,
                            kpm["name"].toString(),
                            KPModel.TriggerEvent.PRE_KERNEL_INIT.event,
                            "",
                            kpm["version"].toString(),
                            kpm["license"].toString(),
                            kpm["author"].toString(),
                            kpm["description"].toString(),
                        )
                        newExtras.add(kpmInfo)
                        newExtrasFileName.add(kpmFileName)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "embedKPM INI error: ${e.message}")
                    error = "Invalid KPM: parse error\n"
                }
            } else {
                error = "Invalid KPM\n"
            }
            running = false
        }
    }

    fun setCustomKPImg(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            if (running) return@launch
            running = true
            error = ""

            val kpimgFile = File(patchDir, "kpimg")
            try {
                uri.inputStream().buffered().use { src ->
                    kpimgFile.also {
                        src.copyAndCloseOut(it.outputStream())
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Copy custom kpimg error: $e")
                error = "Copy custom kpimg error: ${e.message}\n"
                running = false
                return@launch
            }

            customKPImgFileName = getFileNameFromUri(apApp, uri) ?: "kpimg"
            useCustomKPImg = true
            parseKpimg()
            running = false
        }
    }

    fun doUnpatch() {
        viewModelScope.launch(Dispatchers.IO) {
            patching = true
            patchLog = ""
            Log.i(TAG, "starting unpatching...")

            val logs = object : CallbackList<String>() {
                override fun onAddElement(e: String?) {
                    patchLog += e
                    Log.i(TAG, "" + e)
                    patchLog += "\n"
                }
            }

            val result = getShell().newJob().add(
                "export ASH_STANDALONE=1",
                "cd $patchDir",
                "cp /data/adb/ap/ori.img new-boot.img",
                "./busybox sh ./boot_unpatch.sh $bootDev",
                "rm -f ${APApplication.APD_PATH}",
                "rm -rf ${APApplication.APATCH_FOLDER}",
            ).to(logs, logs).exec()

            if (result.isSuccess) {
                logs.add(" Unpatch successful")
                needReboot = true
                APApplication.markNeedReboot()
            } else {
                logs.add(" Unpatched failed")
                error = result.err.joinToString("\n")
            }
            logs.add("****************************")

            patchdone = true
            patching = false
        }
    }

    private fun lkmCommand(vararg args: String): List<String> = listOf("./apd") + args.toList()

    private fun lkmShellCommand(args: List<String>): String = args.joinToString(" ") { arg ->
        "'${arg.replace("'", "'\\''")}'"
    }

    private fun doLkmPatchInternal(mode: PatchMode) {
        val logs = object : CallbackList<String>() {
            override fun onAddElement(e: String?) {
                patchLog += e ?: ""
                patchLog += "\n"
                Log.i(TAG, e ?: "")
            }
        }
        val module = File(patchDir.path, "kernelpatch.ko")
        val loader = File(patchDir.path, "apinit")
        val output = File(patchDir.path, "new-boot.img")
        val command = lkmCommand(
            "lkm-patch",
            "--boot", srcBoot.path,
            "--module", module.path,
            "--loader", loader.path,
            "--out", output.path,
        )
        val installDirectly = mode.isLkmInstall
        val success = if (installDirectly) {
            getShell().newJob().add(
                "export ASH_STANDALONE=1",
                "cd ${patchDir.path}",
                lkmShellCommand(command),
                "./busybox sh ./boot_flash.sh '${bootDev.replace("'", "'\\''")}' '${output.path.replace("'", "'\\''")}'",
            ).to(logs, logs).exec().isSuccess
        } else {
            val process = ProcessBuilder(command)
                .directory(patchDir)
                .redirectErrorStream(true)
                .start()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.forEachLine { line -> logs.add(line) }
            }
            process.waitFor() == 0
        }

        if (!success) {
            error = " LKM patch failed."
            logs.add(error)
            logs.add("****************************")
            patchdone = true
            patching = false
            return
        }

        if (mode.isLkmPatchOnly) {
            val apVer = Version.getManagerVersion().second
            val rand = (1..4).map { ('a'..'z').random() }.joinToString("")
            val outFilename = "folk_lkm_patched_${apVer}_${lkmKmi}_$rand.img"
            val outDir = getSafeDownloadsDir(apApp)
            if (!outDir.exists()) outDir.mkdirs()
            val outPath = File(outDir, outFilename)
            val inputUri = output.getUri(apApp)
            val exported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val outUri = createDownloadUri(apApp, outFilename)
                insertDownload(apApp, outUri, inputUri)
            } else {
                output.inputStream().copyAndClose(outPath.outputStream())
                true
            }
            if (exported) {
                logs.add(apApp.getString(R.string.patch_output_written_to))
                logs.add(" ${outPath.path}")
            } else {
                logs.add(apApp.getString(R.string.patch_write_failed))
            }
        } else {
            if (mode == PatchMode.LKM_INSTALL_TO_NEXT_SLOT) {
                logs.add("- Connecting boot hal...")
                val bootctlStatus = getShell().newJob().add(
                    "cd ${patchDir.path}",
                    "chmod 0777 ${patchDir.path}/bootctl",
                    "./bootctl hal-info",
                ).to(logs, logs).exec()
                if (bootctlStatus.isSuccess) {
                    val currentSlot = shellForResult(
                        getShell(), "cd ${patchDir.path}", "./bootctl get-current-slot"
                    ).out.firstOrNull()?.trim()
                    val targetSlot = if (currentSlot == "0") 1 else 0
                    logs.add("- Switching to next slot: $targetSlot...")
                    getShell().newJob().add(
                        "cd ${patchDir.path}",
                        "./bootctl set-active-boot-slot $targetSlot",
                    ).to(logs, logs).exec()
                } else {
                    logs.add("[X] Failed to connect to boot hal, switch slot manually")
                }
            }
            logs.add("- LKM boot image flashed")
            needReboot = true
            APApplication.markNeedReboot()
            clearJailbreakMarker()
        }
        logs.add("****************************")
        patchdone = true
        patching = false
    }

    fun doLkmRestore() {
        viewModelScope.launch(Dispatchers.IO) {
            patching = true
            patchLog = ""
            val logs = object : CallbackList<String>() {
                override fun onAddElement(e: String?) {
                    patchLog += e ?: ""
                    patchLog += "\n"
                    Log.i(TAG, e ?: "")
                }
            }
            val output = File(patchDir.path, "new-boot.img")
            val command = lkmCommand(
                "lkm-restore",
                "--boot", srcBoot.path,
                "--out", output.path,
            )
            val process = ProcessBuilder(command)
                .directory(patchDir)
                .redirectErrorStream(true)
                .start()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.forEachLine { line -> logs.add(line) }
            }
            if (process.waitFor() != 0) {
                error = " LKM restore failed."
                logs.add(error)
            } else {
                val outFilename = "folk_lkm_restored_${System.currentTimeMillis()}.img"
                val outDir = getSafeDownloadsDir(apApp)
                if (!outDir.exists()) outDir.mkdirs()
                val outPath = File(outDir, outFilename)
                val exported = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val outUri = createDownloadUri(apApp, outFilename)
                        insertDownload(apApp, outUri, output.getUri(apApp))
                    } else {
                        output.inputStream().copyAndClose(outPath.outputStream())
                        true
                    }
                }.onFailure { Log.e(TAG, "LKM restore export failed", it) }.getOrDefault(false)
                if (exported) {
                    logs.add(apApp.getString(R.string.patch_output_written_to))
                    logs.add(" ${outPath.path}")
                } else {
                    logs.add(apApp.getString(R.string.patch_write_failed))
                }
            }
            logs.add("****************************")
            patchdone = true
            patching = false
        }
    }

    fun doPatch(mode: PatchMode, useKey: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            patching = true
            Log.d(TAG, "starting patching...")

            if (mode.isLkm) {
                if (mode.isLkmRestore) {
                    patching = false
                    doLkmRestore()
                } else {
                    doLkmPatchInternal(mode)
                }
                return@launch
            }

            val apVer = Version.getManagerVersion().second
            val rand = (1..4).map { ('a'..'z').random() }.joinToString("")
            val outFilename = "folk_patched_${apVer}_${BuildConfig.buildKPV}_${rand}.img"

            val logs = object : CallbackList<String>() {
                override fun onAddElement(e: String?) {
                    patchLog += e
                    Log.d(TAG, "" + e)
                    patchLog += "\n"
                }
            }
            // Auto Backup Boot
            val prefs = APApplication.sharedPreferences
            if (prefs.getBoolean("auto_backup_boot", false)) {
                logs.add("****************************")
                logs.add(" Backing up boot image...")
                try {
                    val backupDir = File("/storage/emulated/0/Download/FolkPatch/BootBackups/")
                    if (!backupDir.exists()) {
                        backupDir.mkdirs()
                    }
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val backupFile = File(backupDir, "boot_backup_$timestamp.img")
                    srcBoot.inputStream().use { input ->
                        backupFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    logs.add(" Boot image backed up to ${backupFile.absolutePath}")
                } catch (e: Exception) {
                    logs.add(" Backup failed: ${e.message}")
                    Log.e(TAG, "Backup failed", e)
                }
            }

            if (mode == PatchMode.RESTORE) {
                logs.add(" Restoring boot image...")
                val restoreCommand = mutableListOf("./busybox", "sh", "boot_flash.sh", bootDev, srcBoot.path)
                
                val result = getShell().newJob().add(
                    "export ASH_STANDALONE=1",
                    "cd $patchDir",
                    restoreCommand.joinToString(" "),
                ).to(logs, logs).exec()

                if (result.isSuccess) {
                    logs.add(" Restore successful")
                    needReboot = true
                    APApplication.markNeedReboot()
                } else {
                    logs.add(" Restore failed")
                    error = result.err.joinToString("\n")
                }
                logs.add("****************************")
                patchdone = true
                patching = false
                return@launch
            }

            var patchCommand = mutableListOf("./busybox sh boot_patch.sh \"$0\" \"$@\"")

            val installDirectly = mode == PatchMode.PATCH_AND_INSTALL || mode == PatchMode.INSTALL_TO_NEXT_SLOT

            val superkey = if (useKey && this@PatchesViewModel.superkey.isNotEmpty()) {
                this@PatchesViewModel.superkey
            } else {
                "su"
            }

            if (installDirectly) {
                patchCommand = mutableListOf("./busybox", "sh", "boot_patch.sh")
                patchCommand.addAll(listOf(superkey, srcBoot.path, "true"))
            } else {
                patchCommand.addAll(0, listOf("sh", "-c"))
                patchCommand.addAll(listOf(superkey, srcBoot.path))
            }

            for (i in 0..<newExtrasFileName.size) {
                patchCommand.addAll(listOf("-M", newExtrasFileName[i]))
                val extra = newExtras[i]
                if (extra.args.isNotEmpty()) {
                    patchCommand.addAll(listOf("-A", extra.args))
                }
                if (extra.event.isNotEmpty()) {
                    patchCommand.addAll(listOf("-V", extra.event))
                }
                patchCommand.addAll(listOf("-T", extra.type.desc))
            }

            for (i in 0..<existedExtras.size) {
                val extra = existedExtras[i]
                patchCommand.addAll(listOf("-E", extra.name))
                if (extra.args.isNotEmpty()) {
                    patchCommand.addAll(listOf("-A", extra.args))
                }
                if (extra.event.isNotEmpty()) {
                    patchCommand.addAll(listOf("-V", extra.event))
                }
                patchCommand.addAll(listOf("-T", extra.type.desc))
            }

            val builder = ProcessBuilder(patchCommand)

            Log.i(TAG, "patchCommand: $patchCommand")

            var succ = false

            if (installDirectly) {
                val resultString = "\"" + patchCommand.joinToString(separator = "\" \"") + "\""
                val result = getShell().newJob().add(
                    "export ASH_STANDALONE=1",
                    "cd $patchDir",
                    resultString,
                ).to(logs, logs).exec()
                succ = result.isSuccess
            } else {
                builder.environment().put("ASH_STANDALONE", "1")
                builder.directory(patchDir)
                builder.redirectErrorStream(true)

                try {
                    val process = builder.start()

                    Thread {
                        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                patchLog += line
                                Log.i(TAG, "" + line)
                                patchLog += "\n"
                            }
                        }
                    }.start()
                    val exitCode = process.waitFor()
                    succ = exitCode == 0
                    if (!succ) {
                        logs.add("- Patch process exited with code: $exitCode")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start patch process", e)
                    logs.add("- Failed to start patch process: ${e.message}")
                }
            }

            if (!succ) {
                val msg = " Patch failed."
                error = msg
//                error += result.err.joinToString("\n")
                logs.add(error)
                logs.add("****************************")
                patchdone = true
                patching = false
                return@launch
            }

            if (mode == PatchMode.PATCH_AND_INSTALL) {
                logs.add("- Reboot to finish the installation...")
                needReboot = true
                APApplication.markNeedReboot()
                clearJailbreakMarker()
            } else if (mode == PatchMode.INSTALL_TO_NEXT_SLOT) {
                logs.add("- Connecting boot hal...")
                val bootctlStatus = getShell().newJob().add(
                    "cd $patchDir", "chmod 0777 $patchDir/bootctl", "./bootctl hal-info"
                ).to(logs, logs).exec()
                if (!bootctlStatus.isSuccess) {
                    logs.add("[X] Failed to connect to boot hal, you may need switch slot manually")
                } else {
                    val currSlot = shellForResult(
                        getShell(), "cd $patchDir", "./bootctl get-current-slot"
                    ).out.toString()
                    val targetSlot = if (currSlot.contains("0")) {
                        1
                    } else {
                        0
                    }
                    logs.add("- Switching to next slot: $targetSlot...")
                    val setNextActiveSlot = getShell().newJob().add(
                        "cd $patchDir", "./bootctl set-active-boot-slot $targetSlot"
                    ).exec()
                    if (setNextActiveSlot.isSuccess) {
                        logs.add("- Switch done")
                        logs.add("- Writing boot marker script...")
                        val markBootableScript = getShell().newJob().add(
                            "mkdir -p /data/adb/post-fs-data.d && rm -rf /data/adb/post-fs-data.d/post_ota.sh && touch /data/adb/post-fs-data.d/post_ota.sh",
                            "echo \"chmod 0777 $patchDir/bootctl\" > /data/adb/post-fs-data.d/post_ota.sh",
                            "echo \"chown root:root 0777 $patchDir/bootctl\" > /data/adb/post-fs-data.d/post_ota.sh",
                            "echo \"$patchDir/bootctl mark-boot-successful\" > /data/adb/post-fs-data.d/post_ota.sh",
                            "echo >> /data/adb/post-fs-data.d/post_ota.sh",
                            "echo \"rm -rf $patchDir\" >> /data/adb/post-fs-data.d/post_ota.sh",
                            "echo >> /data/adb/post-fs-data.d/post_ota.sh",
                            "echo \"rm -f /data/adb/post-fs-data.d/post_ota.sh\" >> /data/adb/post-fs-data.d/post_ota.sh",
                            "chmod 0777 /data/adb/post-fs-data.d/post_ota.sh",
                            "chown root:root /data/adb/post-fs-data.d/post_ota.sh",
                        ).to(logs, logs).exec()
                        if (markBootableScript.isSuccess) {
                            logs.add("- Boot marker script write done")
                        } else {
                            logs.add("[X] Boot marker scripts write failed")
                        }
                    }
                }
                logs.add("- Reboot to finish the installation...")
                needReboot = true
                APApplication.markNeedReboot()
                clearJailbreakMarker()
            } else if (mode == PatchMode.PATCH_ONLY) {
                val newBootFile = patchDir.getChildFile("new-boot.img")
                val outDir = getSafeDownloadsDir(apApp)
                if (!outDir.exists()) outDir.mkdirs()
                val outPath = File(outDir, outFilename)
                val inputUri = newBootFile.getUri(apApp)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val outUri = createDownloadUri(apApp, outFilename)
                    succ = insertDownload(apApp, outUri, inputUri)
                } else {
                    newBootFile.inputStream().copyAndClose(outPath.outputStream())
                }
                if (succ) {
                    logs.add(apApp.getString(R.string.patch_output_written_to))
                    logs.add(" ${outPath.path}")
                } else {
                    logs.add(apApp.getString(R.string.patch_write_failed))
                }
            }
            logs.add("****************************")
            patchdone = true
            patching = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun createDownloadUri(context: Context, outFilename: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, outFilename)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun insertDownload(context: Context, outUri: Uri?, inputUri: Uri): Boolean {
        if (outUri == null) return false

        try {
            val resolver = context.contentResolver
            SafeUriResolver.openInputStream(context, inputUri)?.use { inputStream ->
                resolver.openOutputStream(outUri)?.use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(outUri, contentValues, null, null)

            return true
        } catch (_: FileNotFoundException) {
            return false
        }
    }

    fun File.getUri(context: Context): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, this)
    }

}
