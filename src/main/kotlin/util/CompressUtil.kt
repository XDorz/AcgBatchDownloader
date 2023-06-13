package util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import net.sf.sevenzipjbinding.simple.ISimpleInArchiveItem
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

class CompressUtil {

    //如果要自定义后缀解压请修改此列表
    var compressSuffix = mutableListOf("zip", "rar", "7z")

    suspend fun compressAll(
        basePath: String,
        recursion: Boolean = true,
        deleteZip: Boolean = true,
        passwordGeneric: CompressUtil.(index: Int?, filename: String, currentFile: File) -> String? = { _, _, _ -> null }
    ): ConcurrentLinkedDeque<String> {
        //创建线程安全list
        val list = ConcurrentLinkedDeque<String>()
        //初始化库，需要有对应平台的依赖
        SevenZip.initSevenZipFromPlatformJAR()
        //协程作用域确保解压全部完成
        coroutineScope {
            File(basePath).let { baseFile ->
                if (baseFile.isDirectory) {
                    //按后缀寻找需要解压文件，多线程进行解压
                    baseFile.flattenPath().filter { it.extension.lowercase() in compressSuffix }.forEach {
                        launch(Dispatchers.IO) {
                            compressZip(it, it.parentFile, recursion, deleteZip, list, passwordGeneric)
                        }
                    }
                } else {
                    //单文件解压不使用多线程
                    compressZip(baseFile, baseFile.parentFile, recursion, deleteZip, list, passwordGeneric)
                }
            }
        }

        return list
    }

    //解压单个zip项，会在所有项被解压后(指定recursion后会包括内部压缩文件)返回
    private suspend fun compressZip(
        zipFile: File,
        saveFile: File,
        recursion: Boolean,
        deleteZip: Boolean,
        list: ConcurrentLinkedDeque<String>,
        passwordGeneric: CompressUtil.(Int?, String, File) -> String?
    ): Unit = coroutineScope {
        //打开压缩文件并获取IInArchive对象
        val accessFile = RandomAccessFile(zipFile, "rw")
        val archive: IInArchive = try {
            passwordGeneric(null, zipFile.name, zipFile)?.let {
                SevenZip.openInArchive(null, RandomAccessFileInStream(accessFile), it)
            } ?: SevenZip.openInArchive(null, RandomAccessFileInStream(accessFile))
        } catch (e: Exception) {
            //无法解压则将文件路径加入失败列表
            println(e.stackTraceToString())
            list.add(zipFile.absolutePath)
            return@coroutineScope
        }


        /**
         * 创建加密项暂存和递归压缩文件暂存
         * 在指定递归情况下是先解压未压缩文件，再解压递归文件，再解压加密文件，最后多线程解压加密文件内的压缩文件
         * 故密码生成器可获取的密码文件范围在：
         *  1.本就存在硬盘的文件，比如你可以在一堆压缩文件的同目录或者父目录下放置密码映射文件
         *  2.压缩文件内未加密文件
         *  3.压缩文件【1】内的压缩文件【2】内的文件(前提是压缩文件【2】内的密码文件能被解压出来(即密码生成器能给出密码))
         *  4.父压缩文件(即该压缩文件是被父压缩文件解压出来的)中的未加密文件(这是被递归解压的文件视角)
         *  5.在它之前被解压的加密项(加密文件的解压顺序以sevenzipjbinding给出的默认顺序为基准，暂时未发现它的item获取顺序是怎么样的，但可以保证在多次运行时获取的顺序不会变)
         */
        /**
         * 创建加密项暂存和递归压缩文件暂存
         * 在指定递归情况下是先解压未压缩文件，再解压递归文件，再解压加密文件，最后多线程解压加密文件内的压缩文件
         * 故密码生成器可获取的密码文件范围在：
         *  1.本就存在硬盘的文件，比如你可以在一堆压缩文件的同目录或者父目录下放置密码映射文件
         *  2.压缩文件内未加密文件
         *  3.压缩文件【1】内的压缩文件【2】内的文件(前提是压缩文件【2】内的密码文件能被解压出来(即密码生成器能给出密码))
         *  4.父压缩文件(即该压缩文件是被父压缩文件解压出来的)中的未加密文件(这是被递归解压的文件视角)
         *  5.在它之前被解压的加密项(加密文件的解压顺序以sevenzipjbinding给出的默认顺序为基准，暂时未发现它的item获取顺序是怎么样的，但可以保证在多次运行时获取的顺序不会变)
         */
        val encryptedFile: MutableList<ISimpleInArchiveItem> = ArrayList()
        val innerZip: MutableList<File> = ArrayList()
        for (item in archive.simpleInterface.archiveItems) {
            if (item.isFolder) {
                //SevenZipJBinding 这个库好像会递归的获得所有文件与文件夹(一般是文件在先，文件夹后于它包含的文件)
                continue
            }

            //非加密项直接解压，并验证是否为套娃压缩文件(指定递归前提下)，将加密文件加入暂存列表
            if (!item.isEncrypted) {
                val realFile = unzipItem(item, saveFile, zipFile, list, null)
                realFile?.let {
                    if (recursion && it.extension.lowercase() in compressSuffix) innerZip.add(it)
                }
            } else {
                encryptedFile.add(item)
                continue
            }
        }

        //如果指定递归解压则完成递归解压
        //只有在指定了递归解压innerZip才会有数据
        //此处制定协程作用域保证将递归解压操作置于加密项解压之前
        coroutineScope {
            innerZip.forEach {
                launch(Dispatchers.IO) {
                    compressZip(it, it.parentFile, recursion, deleteZip, list, passwordGeneric)
                }
            }
        }
        innerZip.clear()

        //解压加密项
        encryptedFile.forEach {
            val realFile = unzipItem(it, saveFile, zipFile, list, passwordGeneric)
            //存储加密项中的递归解压
            realFile?.let { file ->
                if (recursion && file.extension.lowercase() in compressSuffix) innerZip.add(file)
            }
        }
        //递归解压加密项中的zip
        if (recursion) {
            //此处无需使用协程作用域，该处操作位解压已经被解压出来的压缩包，已经和源压缩包无关了
            //如果这里面的压缩文件依赖于兄弟压缩文件内的密码文件，那只能乞求那个兄弟文件中的密码文件先被解压出来了 :)
            innerZip.forEach {
                launch(Dispatchers.IO) {
                    compressZip(it, it.parentFile, recursion, deleteZip, list, passwordGeneric)
                }
            }
        }

        //资源释放
        archive.close()
        accessFile.close()
        if (deleteZip) zipFile.delete()
    }

    private fun unzipItem(
        item: ISimpleInArchiveItem,
        saveFile: File,
        zipFile: File,
        list: ConcurrentLinkedDeque<String>,
        passwordGeneric: (CompressUtil.(index: Int?, filename: String, currentFile: File) -> String?)?
    ): File? {
        //为item创建存放内容的文件，由于上个方法调用已经过滤掉文件夹类型，故不再验证
        val realFile = saveFile.resolve(item.path).let {
            if (it.exists()) {
                //为命名重复文件加入8位uuid开头
                val newFile = it.randomPrefixFile()
                newFile.parentFile.md()
                newFile.createNewFile()
                newFile
            } else {
                it.parentFile.md()
                it.createNewFile()
                it
            }
        }

        //使用NIO进行写入
        val realFileNIO = RandomAccessFile(realFile, "rw")

        //定义数据写入lambda，该lambda会被多次调用，故不能在这里打开和关闭流
        val method = fun(bty: ByteArray): Int {
            realFileNIO.write(bty)
            return bty.size
        }

        //此flag用于控制失败的文件的删除，文件删除要在RandomAccessFile的流关闭之后
        var flag = false
        try {
            //没有给出密码生成器代表无密码
            passwordGeneric?.let { generator ->
                //密码生成器返回null代表无密码
                generator(item.itemIndex, item.path, zipFile)?.let { password ->
                    item.extractSlow(method, password)
                } ?: let {
                    println("###CompressWarning:请为压缩文件加密压缩项给出一个密码，否则该文件数据无法解压---`${saveFile.absolutePath}\\${item.path}` ###")
                    list.add(zipFile.absolutePath + "\\[${item.path}]")
                    item.extractSlow(method)
                }
            } ?: item.extractSlow(method)
        } catch (e: Exception) {
            println(e.stackTraceToString())
            flag = true
            list.add(zipFile.absolutePath + "\\[${item.path}]")
        } finally {
            realFileNIO.close()
        }

        return if (flag) {
            realFile.delete()
            null
        } else {
            realFile
        }
    }

    //展平文件，获取该文件夹内所有的文件
    private fun File.flattenPath(list: MutableList<File> = arrayListOf()): List<File> {
        if (!isDirectory) {
            list.add(this)
        } else {
            listFiles().forEach { it.flattenPath(list) }
        }
        return list
    }

    //获取文件的拓展小写名，kotlin给出了extension属性处理更为得当，此方法半废弃
    private fun File.lowerExtension(): String? {
        return name.let {
            if (it.lastIndexOf('.') >= 0 && it.lastIndexOf('.') < it.length - 1) {
                it.substring(it.lastIndexOf('.') + 1).lowercase()
            } else {
                null
            }
        }
    }

    //获取文件的名称，用于在密码生成器的实现中使用fileName.fileName(),fileName在给出压缩文件内部加密项的时候fileName不是项的名称而是相对路径
    fun String.fileName(): String {
        var s = this
        s = s.replace("/", "\\")
        if (!s.contains("\\")) return s
        return s.substringAfterLast('\\')
    }

    //保证文件夹存在
    private fun File.md() = this.apply {
        if (!exists()) mkdirs()
    }

    suspend fun batchFileMV(savePath: File, fileSet: Set<Pair<String, File>>) {
        coroutineScope {
            fileSet.forEach { pair ->
                launch(Dispatchers.IO) {
                    fileMV(savePath, pair)
                }
            }
        }
    }

    fun fileMV(savePath: File, fileInfo: Pair<String, File>) {
        savePath.md()
        fileInfo.let { (name, file) ->
            val newFile = savePath.resolve(name).let {
                if (it.exists()) {
                    val randomPrefixFile = it.randomPrefixFile()
                    randomPrefixFile.createNewFile()
                    randomPrefixFile
                } else {
                    it.createNewFile()
                    it
                }
            }
            newFile.outputStream().use { fout ->
                file.inputStream().use { fin ->
                    fin.copyTo(fout)
                }
            }
            file.delete()
        }
    }

    @JvmName("fileMovie")
    fun File.fileMV(fileInfo: Pair<String, File>) = fileMV(this, fileInfo)
    //suspend fun File.batchFileMV(fileSet: Set<Pair<String, File>>) = batchFileMV(this, fileSet)

    //为文件重命名一个带有随机数前缀的文件
    //***仅在创建文件时避免同名使用，该方法并不涉及文件实体的操作***
    private fun File.randomPrefixFile(): File =
        parentFile.resolve(UUID.randomUUID().toString().substring(0, 8) + "_" + name)
}