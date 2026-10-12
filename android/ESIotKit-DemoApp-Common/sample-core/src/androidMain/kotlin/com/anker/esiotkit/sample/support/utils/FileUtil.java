package com.anker.esiotkit.sample.support.utils;

import android.graphics.Bitmap;
import android.net.Uri;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FileUtil {
    private static final String TAG = FileUtil.class.getSimpleName();


    public static boolean isFileExist(String fullPath) {
        if (fullPath == null) return false;
        File file = new File(fullPath);
        return file.exists();
    }


    public static boolean createDir(String path) {
        File dir = new File(path);
        return dir.exists() || dir.mkdirs();
    }


    public static boolean deleteFile(File file) {
        return !(file != null && file.exists()) || file.delete();
    }

    public static File[] listFile(String path) {
        File dir = new File(path);
        if (!dir.exists()) return null;
        return dir.listFiles();
    }

    public static File cpFile(File src, String path) {
        try {
            FileInputStream inStream = new FileInputStream(src);
            FileOutputStream outStream = new FileOutputStream(path);
            FileChannel inChannel = inStream.getChannel();
            FileChannel outChannel = outStream.getChannel();
            inChannel.transferTo(0, inChannel.size(), outChannel);
            inStream.close();
            outStream.close();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        File dst = new File(path);
        if (!dst.exists()) return null;
        return dst;
    }

    public static void findAllFile(String path, ArrayList<File> files, FileFilter filter) {
        if (null == path || null == files) {
            return;
        }

        File file = new File(path);

        if (!file.exists()) {
            return;
        }

        if (file.isDirectory()) {
            File[] list = file.listFiles();

            for (File fi : list) {
                if (fi.isDirectory()) {
                    findAllFile(fi.getAbsolutePath(), files, filter);
                } else {
                    if ((null == filter) || (filter.accept(fi))) {
                        files.add(fi);
                    }
                }
            }

        }

        if ((null == filter) || (filter.accept(file))) {
            files.add(file);
        }

    }

    /**
     * 删除目录
     *
     * @param filePath
     * @return
     */
    public static boolean deleteDirectory(String filePath) {
        if (null == filePath) {
            return false;
        }

        File file = new File(filePath);

        if (!file.exists()) {
            return false;
        }

        if (file.isDirectory()) {
            File[] list = file.listFiles();

            for (File fi : list) {
                if (fi.isDirectory()) {
                    deleteDirectory(fi.getAbsolutePath());
                } else {
                    fi.delete();
                }
            }

        }

        file.delete();
        return true;
    }

    public static boolean deleteDirectory(File file) {
        if (file == null || !file.exists()) {
            return false;
        }

        if (file.isDirectory()) {
            File[] list = file.listFiles();

            for (File fi : list) {
                if (fi.isDirectory()) {
                    deleteDirectory(fi.getAbsolutePath());
                } else {
                    fi.delete();
                }
            }

        }
        file.delete();
        return true;
    }

    /**
     * 重命名文件
     *
     * @param oldPath
     * @param newPath
     */
    public static void renameFile(String oldPath, String newPath) {
        if (TextUtils.isEmpty(oldPath) || TextUtils.isEmpty(newPath)) {
            return;
        }

        File file = new File(oldPath);

        if (file.exists()) {
            file.renameTo(new File(newPath));
        }
    }

    /**
     * 重命名文件
     *
     * @param oldFile
     * @param newPath
     */
    public static void renameFile(File oldFile, String newPath) {
        if (TextUtils.isEmpty(newPath)) {
            return;
        }

        if (oldFile.exists()) {
            oldFile.renameTo(new File(newPath));
        }
    }

    public static void createFile(String path) {
        try {
            File file = new File(path);
            file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void writeFile(final String path, final String text, boolean append) {
        try {
            File file = new File(path);
            FileOutputStream fileOut2 = new FileOutputStream(file, append);
            PrintStream out2 = new PrintStream(fileOut2);
            out2.println(text);
            out2.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    /**
     * TAG for log messages.
     */
    private static final boolean DEBUG = false; // Set to true to enable logging

    public static final String MIME_TYPE_AUDIO = "audio/*";
    public static final String MIME_TYPE_TEXT = "text/*";
    public static final String MIME_TYPE_IMAGE = "image/*";
    public static final String MIME_TYPE_VIDEO = "video/*";
    public static final String MIME_TYPE_APP = "application/*";

    public static final String HIDDEN_PREFIX = ".";

    /**
     * Gets the extension of a file name, like ".png" or ".jpg".
     *
     * @param uri
     * @return Extension including the dot("."); "" if there is no extension;
     * null if uri was null.
     */
    public static String getExtension(String uri) {
        if (uri == null) {
            return null;
        }

        int dot = uri.lastIndexOf(".");
        if (dot >= 0) {
            return uri.substring(dot);
        } else {
            // No extension.
            return "";
        }
    }

    /**
     * @return Whether the URI is a local one.
     */
    public static boolean isLocal(String url) {
        return url != null && !url.startsWith("http://") && !url.startsWith("https://");
    }

    /**
     * @return True if Uri is a MediaStore Uri.
     */
    public static boolean isMediaUri(Uri uri) {
        return "media".equalsIgnoreCase(uri.getAuthority());
    }

    /**
     * Convert File into Uri.
     *
     * @param file
     * @return uri
     */
    public static Uri getUri(File file) {
        if (file != null) {
            return Uri.fromFile(file);
        }
        return null;
    }

    /**
     * Returns the path only (without file name).
     *
     * @param file
     * @return
     */
    public static File getPathWithoutFilename(File file) {
        if (file != null) {
            if (file.isDirectory()) {
                // no file to be split off. Return everything
                return file;
            } else {
                String filename = file.getName();
                String filepath = file.getAbsolutePath();

                // Construct path without file name.
                String pathwithoutname = filepath.substring(0,
                        filepath.length() - filename.length());
                if (pathwithoutname.endsWith("/")) {
                    pathwithoutname = pathwithoutname.substring(0, pathwithoutname.length() - 1);
                }
                return new File(pathwithoutname);
            }
        }
        return null;
    }

    /**
     * @return The MIME type for the given file.
     */
    public static String getMimeType(File file) {

        String extension = getExtension(file.getName());

        if (extension.length() > 0)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.substring(1));

        return "application/octet-stream";
    }

    /**
     * Get the file size in a human-readable string.
     *
     * @param size
     * @return
     */
    public static String getReadableFileSize(int size) {
        final int BYTES_IN_KILOBYTES = 1024;
        final DecimalFormat dec = new DecimalFormat("###.#");
        final String KILOBYTES = " KB";
        final String MEGABYTES = " MB";
        final String GIGABYTES = " GB";
        float fileSize = 0;
        String suffix = KILOBYTES;

        if (size > BYTES_IN_KILOBYTES) {
            fileSize = size / BYTES_IN_KILOBYTES;
            if (fileSize > BYTES_IN_KILOBYTES) {
                fileSize = fileSize / BYTES_IN_KILOBYTES;
                if (fileSize > BYTES_IN_KILOBYTES) {
                    fileSize = fileSize / BYTES_IN_KILOBYTES;
                    suffix = GIGABYTES;
                } else {
                    suffix = MEGABYTES;
                }
            }
        }
        return String.valueOf(dec.format(fileSize) + suffix);
    }


    /**
     * 获取指定文件大小
     *
     * @return
     * @throws Exception
     */
    public static long getFileSize(File file) {
        long size = 0;
        try {
            if (file.exists()) {
                FileInputStream fis = null;
                fis = new FileInputStream(file);
                size = fis.available();
            }
        } catch (Exception e) {
        }
        return size;
    }

    /**
     * 清空文件夹
     */
    public static void clearDir(File dir, boolean isDeleteDir) {
        if (dir.exists()) {// 判断文件是否存在
            if (dir.isFile()) {// 判断是否是文件
                dir.delete();// 删除文件
            } else if (dir.isDirectory()) {// 否则如果它是一个目录
                File[] files = dir.listFiles();// 声明目录下所有的文件 files[];
                for (int i = 0; i < files.length; i++) {// 遍历目录下所有的文件
                    clearDir(files[i], true);// 把每个文件用这个方法进行迭代
                }
                if (isDeleteDir) {
                    dir.delete();// 删除文件夹
                }
            }
        }
    }

    /**
     * 创建压缩文件
     *
     * @param sourceFolderPath
     * @param zipFilePath
     * @return
     * @throws IOException
     */
    public static File createZipFile(String sourceFolderPath, String zipFilePath) throws IOException {
        FileOutputStream fos = new FileOutputStream(zipFilePath);
        ZipOutputStream zos = new ZipOutputStream(fos);
        File sourceFolder = new File(sourceFolderPath);
        zipSubFolder(zos, sourceFolder, sourceFolder.getParent().length());
        zos.close();
        return new File(zipFilePath);
    }

    public static void zipSubFolder(ZipOutputStream zos, File folder, int basePathLength) throws IOException {
        byte[] buffer = new byte[4096];
        File[] files = folder.listFiles();
        for (File file : files) {
            if (file.isDirectory()) {
                zipSubFolder(zos, file, basePathLength);
            } else {
                FileInputStream fis = new FileInputStream(file);
                String entryPath = file.getAbsolutePath().substring(basePathLength);
                ZipEntry entry = new ZipEntry(entryPath);
                zos.putNextEntry(entry);
                int count;
                while ((count = fis.read(buffer)) != -1) {
                    zos.write(buffer, 0, count);
                }
                fis.close();
            }
        }
    }

    public static void saveImageToFile(Bitmap resource, String savePath) throws IOException {
        File file = new File(savePath);
        try {
            FileOutputStream fos = new FileOutputStream(file);
            resource.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void saveTextToFile(String text, String savePath) throws IOException {
        File file = new File(savePath);
        try {
            FileWriter writer = new FileWriter(file);
            writer.write(text);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
//    /**
//     * 获取手机内部可用空间大小
//     *
//     * @return
//     */
//    static public long getAvailableInternalMemorySize() {
//        File path = Environment.getDataDirectory();
//        StatFs stat = new StatFs(path.getPath());
//        long blockSize = stat.getBlockSizeLong();
//        long availableBlocks = stat.getAvailableBlocksLong();
//        return availableBlocks * blockSize;
//    }

//    /**
//     * 获取手机内部空间大小
//     *
//     * @return
//     */
//    static public long getTotalInternalMemorySize() {
//        File path = Environment.getDataDirectory();//Gets the Android data directory
//        StatFs stat = new StatFs(path.getPath());
//        long blockSize = stat.getBlockSizeLong();      //每个block 占字节数
//        long totalBlocks = stat.getBlockCountLong();   //block总数
//        return totalBlocks * blockSize;
//    }

}