package com.anker.esiotkit.sample.main.mega.add_device.device_family

import cafe.adriel.voyager.core.lifecycle.JavaSerializable
import kotlinx.serialization.Serializable

@Serializable
data class DeviceCategory(
  val categoryPlatform: String,
  val categoryName: String,
  val categoryDes: String,
  val categoryCode: String,
  val categoryIcon: String,
  val categoryVersion: String,
  val sortlv: Int,
  val isBlur: Boolean,
  val isNewTag: Boolean,
  val isHidden: Boolean,
  val countryCodeBlackList: List<String>,
  val countryCodeWhiteList: List<String>,
  val deviceList: List<Device>
) : JavaSerializable

@Serializable
data class Device(
  val devicePNs: List<String>,
  val deviceSNREs: List<String>,
  val icon: String,
  val iconURL: String,
  val deivceCategory: String,
  val deviceCateLv2: String,
  val deviceType: String,
  val deviceTypeName: String,
  val sortlv: Int,
  val isBlur: Boolean,
  val isNewTag: Boolean,
  val isHidden: Boolean,
  val countryCodeBlackList: List<String>,
  val countryCodeWhiteList: List<String>,
  val isFollowNewProcess: Boolean
) : JavaSerializable

@Serializable
data class MergedDevice(
  val deviceCateLv2: String,
  val deviceTypeNames: List<String>,
  val iconURL: String,
  val devicePNs: List<String>,
  val isBlur: Boolean,
  val isNewTag: Boolean,
  val isHidden: Boolean,
  val countryCodeBlackList: List<String>,
  val countryCodeWhiteList: List<String>,
  val isFollowNewProcess: Boolean
) : JavaSerializable
