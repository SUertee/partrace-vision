package com.anker.esiotkit.sample.support.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import com.anker.esiotkit.sample.support.applicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 创建日期：2020/11/25
 * 文件操作类
 */
object StoragePathUtils {
  var DEFAULT_FODER_PIC = "EufyDemoPicture"
  var DEFAULT_FODER_VIDEO = "EufyDemoVideo"
  var DEFAULT_FODER_ZIP = "EufyDemoZip"
  fun getAppDiskVideosDir(context: Context, sku: String?): String {
    return getExternalDirPath(context, Environment.DIRECTORY_MOVIES, sku)
  }

  fun getAppDiskPicDir(context: Context, sku: String?): String {
    return getExternalDirPath(context, Environment.DIRECTORY_PICTURES, sku)
  }

  fun getAppDiskMusicDir(context: Context, sku: String?): String {
    return getExternalDirPath(context, Environment.DIRECTORY_MOVIES, sku)
  }

  fun getVideoPath(context: Context, sku: String?): String {
    return getAppDiskVideosDir(context, sku) +
    DateUtil.timeStamp2Date(System.currentTimeMillis(), "") + ".mp4"
  }

  fun getMusicPath(context: Context, sku: String?): String {
    return getAppDiskMusicDir(context, sku) +
    DateUtil.timeStamp2Date(System.currentTimeMillis(), "") + ".aac"
  }

  fun getPicPath(context: Context, sku: String?): String {
    return getAppDiskPicDir(context, sku) +
    DateUtil.timeStamp2Date(System.currentTimeMillis(), "") + ".jpg"
  }

  fun getExternalDirPath(context: Context, mediaType: String, sku: String?): String {
    var path: String? = null
    return if (Build.VERSION.SDK_INT >= 29) {
      var externalFilesDir: File? =
        context.getExternalFilesDir(mediaType)
      if (externalFilesDir == null) {
        externalFilesDir = context.getFilesDir()
      }
      path = externalFilesDir!!.absolutePath + File.separator
      if (TextUtils.isEmpty(sku)) {
        val file = File(path)
        if (!file.exists()) {
          file.mkdirs()
        }
        path
      } else {
        val file = File(path, sku)
        if (!file.exists()) {
          file.mkdirs()
        }
        file.absolutePath + File.separator
      }
    } else {
      path = when (mediaType) {
        Environment.DIRECTORY_MOVIES -> {
          Environment.getExternalStorageDirectory().absolutePath + File.separator + DEFAULT_FODER_VIDEO + File.separator
        }
        Environment.DIRECTORY_DOWNLOADS -> {
          Environment.getExternalStorageDirectory().absolutePath + File.separator + DEFAULT_FODER_ZIP + File.separator
        }
        else -> {
          Environment.getExternalStorageDirectory().absolutePath + File.separator + DEFAULT_FODER_PIC + File.separator
        }
      }
      val file = File(path)
      if (!file.exists()) {
        file.mkdirs()
      }
      path
    }
  }

  fun saveBitmap(context: Context, bt: Bitmap?) {
    if (null == bt || bt.isRecycled) {
      return
    }
    val imgName = DateUtil.timeStamp2Date(System.currentTimeMillis(), "") + ".jpg"
    if (Build.VERSION.SDK_INT >= 29) { //android 10
      val contentValues = ContentValues()
      contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, imgName)
      contentValues.put(MediaStore.Images.Media.DESCRIPTION, imgName)
      contentValues.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
      contentValues.put(MediaStore.Downloads.RELATIVE_PATH, "Pictures/" + DEFAULT_FODER_PIC)
      val uri: Uri? = context.contentResolver
        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
      uri?.let {
        context.contentResolver.openOutputStream(it)?.use {
          bt.compress(Bitmap.CompressFormat.JPEG, 100, it)
        }
      }
    } else {
      var fileName = (Environment.getExternalStorageDirectory().absolutePath
      + File.separator + DEFAULT_FODER_PIC + File.separator)
      FileUtil.createDir(fileName)
      fileName += imgName
      val file = File(fileName)
      var out: FileOutputStream? = null
      try {
        out = FileOutputStream(file)
        bt.compress(Bitmap.CompressFormat.JPEG, 100, out)
        val uri = Uri.fromFile(file)
        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
      } catch (e: Exception) {
        e.printStackTrace()
      } finally {
        out?.close()
      }
    }
  }

  private fun getMediaName(srcPath: String): String {
    try {
      val pos = srcPath.lastIndexOf(File.separator)
      return srcPath.substring(pos)
    } catch (e: Exception) {
      e.printStackTrace()
    }
    return srcPath
  }

  fun deleteMedia(context: Context, uri: Uri?) {
    context.contentResolver.delete(uri!!, null, null)
  }

  fun shareVideoToGallery(srcPath: String) {
    if (TextUtils.isEmpty(srcPath)) {
      return
    }
    val context = applicationContext as Context

    if (Build.VERSION.SDK_INT >= 29) {
      val contentValues = ContentValues()
      contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, getMediaName(srcPath))
      contentValues.put(MediaStore.Video.Media.DESCRIPTION, getMediaName(srcPath))
      contentValues.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
      contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/" + DEFAULT_FODER_VIDEO)
       try {
        val uri: Uri? = context.getContentResolver()
          .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        val file: File = File(srcPath)
        val inputStream = FileInputStream(file)
        val os: OutputStream? = uri?.let { context.getContentResolver().openOutputStream(it) }
        if (os != null) {
          val buffer = ByteArray(1024)
          var byteCount: Int
          while(inputStream.read(buffer).also { byteCount = it } != -1) {
            os.write(buffer, 0, byteCount)
          }
          os.flush()
          os.close()
          inputStream.close()
        }
        //                file.delete();
      } catch (e: java.lang.Exception) {
        android.util.Log.w("StoragePathUtils", "shareVideoToGallery galleryCopyExc=${e.javaClass.simpleName}:${e.message} src=$srcPath")
        e.printStackTrace()
      }
    } else {
      val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
      val uri = Uri.parse("file://$srcPath")
      intent.data = uri
      context.sendBroadcast(intent)
    }
  }

  val isSDCardEnable: Boolean
    /**
     * 判断SDCard是否可用
     *
     * @return
     */
    get() = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
}