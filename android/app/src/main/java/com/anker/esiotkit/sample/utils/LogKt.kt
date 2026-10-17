package com.anker.esiotkit.sample.utils

import android.os.Environment
import com.anker.esiotkit.sample.MyApplication
import java.io.BufferedWriter
import java.io.FileWriter
import java.io.IOException

/**
@date: 2023/9/5
@desc:
 */
object LogKt {
  fun writeLogToFile(log: String) {

    val path = FileKt.getExternalDirPath(MyApplication.instance, Environment.DIRECTORY_MOVIES, null) ?: ""
    val name = "log.txt"

    val logFile = path + name

    var bw: BufferedWriter? = null

    try {
      // 创建一个FileWriter对象
      val fw = FileWriter(logFile, true) // 第二个参数为true表示追加写入

      // 创建BufferedWriter对象
      bw = BufferedWriter(fw)

      // 写入log
      bw.write(log)

      // 写入一个新行
      bw.newLine()

      // 刷新BufferedWriter的缓冲区
      bw.flush()
    } catch (e: IOException) {
      e.printStackTrace()
    } finally {
      // 关闭BufferedWriter
      if (bw != null) {
        try {
          bw.close()
        } catch (e: IOException) {
          e.printStackTrace()
        }
      }
    }
  }
}