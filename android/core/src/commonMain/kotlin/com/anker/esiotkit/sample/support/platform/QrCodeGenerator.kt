package com.anker.esiotkit.sample.support.platform

/**
 * 生成二维码 PNG 字节数组
 * @param text 二维码内容
 * @param size 图片尺寸（像素）
 * @param version 二维码版本（1-40），Version 3 = 29x29 模块
 * @return PNG 格式的字节数组
 */
expect fun generateQrCode(text: String, size: Int, version: Int = 3): ByteArray
